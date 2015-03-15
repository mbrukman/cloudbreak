package com.sequenceiq.cloudbreak.conf;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.repository.RetryingStackUpdater;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.service.stack.FailureHandlerService;
import com.sequenceiq.cloudbreak.service.stack.connector.CloudPlatformConnector;
import com.sequenceiq.cloudbreak.service.stack.connector.UserDataBuilder;
import com.sequenceiq.cloudbreak.service.stack.flow.ProvisionContext;
import com.sequenceiq.cloudbreak.service.stack.flow.ProvisionUtil;
import com.sequenceiq.cloudbreak.service.stack.resource.ResourceBuilder;
import com.sequenceiq.cloudbreak.service.stack.resource.ResourceBuilderInit;

import reactor.core.Reactor;

@Configuration
public class ContextConfig {
    @Autowired
    private StackRepository stackRepository;

    @Autowired
    private RetryingStackUpdater stackUpdater;

    @javax.annotation.Resource
    private Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors;

    @Autowired
    private Reactor reactor;

    @Autowired
    private UserDataBuilder userDataBuilder;

    @javax.annotation.Resource
    private Map<CloudPlatform, List<ResourceBuilder>> instanceResourceBuilders;

    @javax.annotation.Resource
    private Map<CloudPlatform, List<ResourceBuilder>> networkResourceBuilders;

    @Autowired
    private AsyncTaskExecutor resourceBuilderExecutor;

    @javax.annotation.Resource
    private Map<CloudPlatform, ResourceBuilderInit> resourceBuilderInits;

    @Autowired
    private ProvisionUtil provisionUtil;

    @Autowired
    @Qualifier("stackFailureHandlerService")
    private FailureHandlerService stackFailureHandlerService;

    @Bean
    ProvisionContext provisionContext() {
        return ProvisionContext.ProvisionContextBuilder.builder()
                .withStackRepository(stackRepository)
                .withStackUpdater(stackUpdater)
                .withCloudPlatformConnectors(cloudPlatformConnectors)
                .withInstanceResourceBuilders(instanceResourceBuilders)
                .withNetworkResourceBuilders(networkResourceBuilders)
                .withProvisionUtil(provisionUtil)
                .withReactor(reactor)
                .withResourceBuilderExecutor(resourceBuilderExecutor)
                .withResourceBuilderInits(resourceBuilderInits)
                .withUserDataBuilder(userDataBuilder)
                .withStackFailureHandlerService(stackFailureHandlerService)
                .build();
    }
}
