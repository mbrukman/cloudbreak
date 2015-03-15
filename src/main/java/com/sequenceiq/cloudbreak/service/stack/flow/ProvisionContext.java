package com.sequenceiq.cloudbreak.service.stack.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import com.sequenceiq.cloudbreak.conf.ReactorConfig;
import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.Status;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.cloudbreak.repository.RetryingStackUpdater;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.service.stack.FailureHandlerService;
import com.sequenceiq.cloudbreak.service.stack.connector.CloudPlatformConnector;
import com.sequenceiq.cloudbreak.service.stack.connector.UserDataBuilder;
import com.sequenceiq.cloudbreak.service.stack.event.ProvisionComplete;
import com.sequenceiq.cloudbreak.service.stack.event.StackOperationFailure;
import com.sequenceiq.cloudbreak.service.stack.flow.callable.ProvisionContextCallable.ProvisionContextCallableBuilder;
import com.sequenceiq.cloudbreak.service.stack.resource.CreateResourceRequest;
import com.sequenceiq.cloudbreak.service.stack.resource.ProvisionContextObject;
import com.sequenceiq.cloudbreak.service.stack.resource.ResourceBuilder;
import com.sequenceiq.cloudbreak.service.stack.resource.ResourceBuilderInit;

import reactor.core.Reactor;
import reactor.event.Event;

public class ProvisionContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvisionContext.class);

    private final StackRepository stackRepository;
    private final RetryingStackUpdater stackUpdater;
    private final Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors;
    private final Reactor reactor;
    private final UserDataBuilder userDataBuilder;
    private final Map<CloudPlatform, List<ResourceBuilder>> instanceResourceBuilders;
    private final Map<CloudPlatform, List<ResourceBuilder>> networkResourceBuilders;
    private final AsyncTaskExecutor resourceBuilderExecutor;
    private final Map<CloudPlatform, ResourceBuilderInit> resourceBuilderInits;
    private final ProvisionUtil provisionUtil;
    private final FailureHandlerService stackFailureHandlerService;

    public ProvisionContext(
            StackRepository stackRepository,
            RetryingStackUpdater stackUpdater,
            Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors,
            Reactor reactor, UserDataBuilder userDataBuilder,
            Map<CloudPlatform, List<ResourceBuilder>> instanceResourceBuilders,
            Map<CloudPlatform, List<ResourceBuilder>> networkResourceBuilders,
            AsyncTaskExecutor resourceBuilderExecutor,
            Map<CloudPlatform, ResourceBuilderInit> resourceBuilderInits,
            ProvisionUtil provisionUtil,
            FailureHandlerService stackFailureHandlerService) {
        this.stackRepository = stackRepository;
        this.stackUpdater = stackUpdater;
        this.cloudPlatformConnectors = cloudPlatformConnectors;
        this.reactor = reactor;
        this.userDataBuilder = userDataBuilder;
        this.instanceResourceBuilders = instanceResourceBuilders;
        this.networkResourceBuilders = networkResourceBuilders;
        this.resourceBuilderExecutor = resourceBuilderExecutor;
        this.resourceBuilderInits = resourceBuilderInits;
        this.provisionUtil = provisionUtil;
        this.stackFailureHandlerService = stackFailureHandlerService;
    }

    public void buildStack(final CloudPlatform cloudPlatform, Long stackId, Map<String, Object> setupProperties, Map<String, String> userDataParams) {
        Stack stack = stackRepository.findOneWithLists(stackId);
        MDCBuilder.buildMdcContext(stack);
        try {
            if (stack.getStatus().equals(Status.REQUESTED)) {
                String statusReason = "Creation of cluster infrastructure has started on the cloud provider.";
                stack = stackUpdater.updateStackStatus(stack.getId(), Status.CREATE_IN_PROGRESS, statusReason);
                stackUpdater.updateStackStatusReason(stack.getId(), stack.getStatus().name());
                String userDataScript = userDataBuilder.build(cloudPlatform, stack.getHash(), stack.getConsulServers(), userDataParams);
                if (!cloudPlatform.isWithTemplate()) {
                    stackUpdater.updateStackStatus(stack.getId(), Status.REQUESTED, "Creation of cluster infrastructure has been requested.");
                    Set<Resource> resourceSet = new HashSet<>();
                    ResourceBuilderInit resourceBuilderInit = resourceBuilderInits.get(cloudPlatform);
                    final ProvisionContextObject pCO = resourceBuilderInit.provisionInit(stack, userDataScript);
                    for (ResourceBuilder resourceBuilder : networkResourceBuilders.get(cloudPlatform)) {
                        List<Resource> buildResources = resourceBuilder.buildResources(pCO, 0, Arrays.asList(resourceSet), Optional.<InstanceGroup>absent());
                        CreateResourceRequest createResourceRequest =
                                resourceBuilder.buildCreateRequest(pCO, Lists.newArrayList(resourceSet), buildResources, 0, Optional.<InstanceGroup>absent());
                        stackUpdater.addStackResources(stack.getId(), createResourceRequest.getBuildableResources());
                        resourceSet.addAll(createResourceRequest.getBuildableResources());
                        pCO.getNetworkResources().addAll(createResourceRequest.getBuildableResources());
                        resourceBuilder.create(createResourceRequest, stack.getRegion());
                    }
                    List<Future<ResourceRequestResult>> futures = new ArrayList<>();
                    List<ResourceRequestResult> resourceRequestResults = new ArrayList<>();
                    int fullIndex = 0;
                    for (final InstanceGroup instanceGroupEntry : getOrderedCopy(stack.getInstanceGroups())) {
                        for (int i = 0; i < instanceGroupEntry.getNodeCount(); i++) {
                            final int index = fullIndex;
                            final Stack finalStack = stack;
                            Future<ResourceRequestResult> submit = resourceBuilderExecutor.submit(
                                    ProvisionContextCallableBuilder.builder()
                                            .withIndex(index)
                                            .withInstanceGroup(instanceGroupEntry)
                                            .withInstanceResourceBuilders(instanceResourceBuilders)
                                            .withProvisionContextObject(pCO)
                                            .withStack(finalStack)
                                            .withStackUpdater(stackUpdater)
                                            .withStackRepository(stackRepository)
                                            .build()
                            );
                            futures.add(submit);
                            fullIndex++;
                            if (provisionUtil.isRequestFullWithCloudPlatform(stack, futures.size() + 1)) {
                                resourceRequestResults.addAll(provisionUtil.waitForRequestToFinish(stackId, futures).get(FutureResult.FAILED));
                                stackFailureHandlerService.handleFailure(stack, resourceRequestResults);
                                futures = new ArrayList<>();
                            }
                        }
                    }
                    resourceRequestResults.addAll(provisionUtil.waitForRequestToFinish(stackId, futures).get(FutureResult.FAILED));
                    stackFailureHandlerService.handleFailure(stack, resourceRequestResults);
                    if (!stackRepository.findById(stackId).isStackInDeletionPhase()) {
                        LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.PROVISION_COMPLETE_EVENT, stack.getId());
                        reactor.notify(ReactorConfig.PROVISION_COMPLETE_EVENT, Event.wrap(new ProvisionComplete(cloudPlatform, stack.getId(), resourceSet)));
                    }
                } else {
                    CloudPlatformConnector cloudPlatformConnector = cloudPlatformConnectors.get(cloudPlatform);
                    cloudPlatformConnector.buildStack(stack, userDataScript, setupProperties);
                }
            } else {
                LOGGER.info("CloudFormation stack creation was requested for a stack, that is not in REQUESTED status anymore. [stackId: '{}', status: '{}']",
                        stack.getId(), stack.getStatus());
            }
        } catch (Exception e) {
            LOGGER.error("Unhandled exception occurred while creating stack.", e);
            LOGGER.info("Publishing {} event.", ReactorConfig.STACK_CREATE_FAILED_EVENT);
            StackOperationFailure stackCreationFailure = new StackOperationFailure(stackId, "Internal server error occurred while creating stack: "
                    + e.getMessage());
            reactor.notify(ReactorConfig.STACK_CREATE_FAILED_EVENT, Event.wrap(stackCreationFailure));
        }
    }

    private List<InstanceGroup> getOrderedCopy(Set<InstanceGroup> instanceGroupSet) {
        Ordering<InstanceGroup> byLengthOrdering = new Ordering<InstanceGroup>() {
            public int compare(InstanceGroup left, InstanceGroup right) {
                return Ints.compare(left.getNodeCount(), right.getNodeCount());
            }
        };
        return byLengthOrdering.sortedCopy(instanceGroupSet);
    }


    public static class ProvisionContextBuilder {
        private StackRepository stackRepository;
        private RetryingStackUpdater stackUpdater;
        private Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors;
        private Reactor reactor;
        private UserDataBuilder userDataBuilder;
        private Map<CloudPlatform, List<ResourceBuilder>> instanceResourceBuilders;
        private Map<CloudPlatform, List<ResourceBuilder>> networkResourceBuilders;
        private AsyncTaskExecutor resourceBuilderExecutor;
        private Map<CloudPlatform, ResourceBuilderInit> resourceBuilderInits;
        private ProvisionUtil provisionUtil;
        private FailureHandlerService stackFailureHandlerService;

        public static ProvisionContextBuilder builder() {
            return new ProvisionContextBuilder();
        }

        public ProvisionContextBuilder withStackRepository(StackRepository stackRepository) {
            this.stackRepository = stackRepository;
            return this;
        }

        public ProvisionContextBuilder withStackUpdater(RetryingStackUpdater stackUpdater) {
            this.stackUpdater = stackUpdater;
            return this;
        }

        public ProvisionContextBuilder withCloudPlatformConnectors(Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors) {
            this.cloudPlatformConnectors = cloudPlatformConnectors;
            return this;
        }

        public ProvisionContextBuilder withReactor(Reactor reactor) {
            this.reactor = reactor;
            return this;
        }

        public ProvisionContextBuilder withUserDataBuilder(UserDataBuilder userDataBuilder) {
            this.userDataBuilder = userDataBuilder;
            return this;
        }

        public ProvisionContextBuilder withInstanceResourceBuilders(Map<CloudPlatform, List<ResourceBuilder>> instanceResourceBuilders) {
            this.instanceResourceBuilders = instanceResourceBuilders;
            return this;
        }

        public ProvisionContextBuilder withNetworkResourceBuilders(Map<CloudPlatform, List<ResourceBuilder>> networkResourceBuilders) {
            this.networkResourceBuilders = networkResourceBuilders;
            return this;
        }

        public ProvisionContextBuilder withResourceBuilderExecutor(AsyncTaskExecutor resourceBuilderExecutor) {
            this.resourceBuilderExecutor = resourceBuilderExecutor;
            return this;
        }

        public ProvisionContextBuilder withResourceBuilderInits(Map<CloudPlatform, ResourceBuilderInit> resourceBuilderInits) {
            this.resourceBuilderInits = resourceBuilderInits;
            return this;
        }

        public ProvisionContextBuilder withProvisionUtil(ProvisionUtil provisionUtil) {
            this.provisionUtil = provisionUtil;
            return this;
        }

        public ProvisionContextBuilder withStackFailureHandlerService(FailureHandlerService stackFailureHandlerService) {
            this.stackFailureHandlerService = stackFailureHandlerService;
            return this;
        }

        public ProvisionContext build() {
            return new ProvisionContext(
                    stackRepository,
                    stackUpdater,
                    cloudPlatformConnectors,
                    reactor,
                    userDataBuilder,
                    instanceResourceBuilders,
                    networkResourceBuilders,
                    resourceBuilderExecutor,
                    resourceBuilderInits,
                    provisionUtil,
                    stackFailureHandlerService
            );
        }
    }
}
