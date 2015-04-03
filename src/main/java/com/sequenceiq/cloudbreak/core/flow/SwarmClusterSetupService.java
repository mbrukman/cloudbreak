package com.sequenceiq.cloudbreak.core.flow;

import static com.sequenceiq.cloudbreak.core.flow.ClusterSetupTool.SWARM;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.sequenceiq.cloudbreak.core.CloudbreakException;
import com.sequenceiq.cloudbreak.core.flow.containers.AmbariAgentBootstrap;
import com.sequenceiq.cloudbreak.core.flow.containers.AmbariServerBootstrap;
import com.sequenceiq.cloudbreak.core.flow.containers.AmbariServerDatabaseBootstrap;
import com.sequenceiq.cloudbreak.core.flow.containers.ConsulWatchBootstrap;
import com.sequenceiq.cloudbreak.core.flow.containers.MunchausenBootstrap;
import com.sequenceiq.cloudbreak.core.flow.containers.RegistratorBootstrap;
import com.sequenceiq.cloudbreak.core.flow.context.DockerContext;
import com.sequenceiq.cloudbreak.core.flow.context.FlowContext;
import com.sequenceiq.cloudbreak.core.flow.context.ProvisioningContext;
import com.sequenceiq.cloudbreak.core.flow.context.SwarmContext;
import com.sequenceiq.cloudbreak.domain.HostMetadata;
import com.sequenceiq.cloudbreak.domain.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.repository.ClusterRepository;
import com.sequenceiq.cloudbreak.repository.InstanceMetaDataRepository;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.service.PollingService;

@Service
public class SwarmClusterSetupService implements ClusterSetupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StackStartService.class);
    private static final int TEN = 100;
    private static final int POLLING_INTERVAL = 5000;
    private static final int MAX_POLLING_ATTEMPTS = 100;

    @Value("${cb.ambari.docker.tag:sequenceiq/ambari:1.7.0-consul}")
    private String ambariDockerTag;

    @Autowired
    private StackRepository stackRepository;

    @Autowired
    private ClusterRepository clusterRepository;

    @Autowired
    private InstanceMetaDataRepository instanceMetaDataRepository;

    @Autowired
    private PollingService<DockerContext> dockerInfoPollingService;

    @Autowired
    private DockerCheckerTask dockerCheckerTask;

    @Autowired
    private PollingService<SwarmContext> swarmInfoPollingService;

    @Autowired
    private SwarmCheckerTask swarmCheckerTask;

    @Autowired
    private DockerImageCheckerTask dockerImageCheckerTask;

    @Override
    public void preSetup(Long stackId, InstanceGroup gateway, Set<InstanceGroup> hostGroupTypeGroups) throws CloudbreakException {
        try {
            Stack stack = stackRepository.findOneWithLists(stackId);
            InstanceMetaData gatewayData = gateway.getInstanceMetaData().iterator().next();
            String consulServers = getConsulServers(gatewayData, hostGroupTypeGroups, stack.getConsulServers());
            String consulJoinIps = getConsulJoinIps(stack.getInstanceGroups());
            DockerClient dockerApiClient = DockerClientBuilder.getInstance(getDockerClientConfig(stack.getAmbariIp())).build();
            dockerInfoPollingService.pollWithTimeout(dockerCheckerTask, new DockerContext(stack, dockerApiClient), POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
            dockerInfoPollingService.pollWithTimeout(dockerImageCheckerTask, new DockerContext(stack, dockerApiClient), POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
            String cmd[] = { "--debug", "bootstrap", "--consulServers", consulServers, consulJoinIps };
            new MunchausenBootstrap(dockerApiClient, gatewayData.getPrivateIp(), cmd).call();
        } catch (Exception ex) {
            throw new CloudbreakException(ex);
        }
    }

    @Override
    public void preSetupNewNode(Long stackId, InstanceGroup gateway, List<HostMetadata> hostMetadata) throws CloudbreakException {
        Stack stack = stackRepository.findOneWithLists(stackId);
        InstanceMetaData gatewayData = gateway.getInstanceMetaData().iterator().next();
        DockerClient dockerApiClient = DockerClientBuilder.getInstance(getDockerClientConfig(stack.getAmbariIp())).build();
        dockerInfoPollingService.pollWithTimeout(dockerCheckerTask, new DockerContext(stack, dockerApiClient), POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
        dockerInfoPollingService.pollWithTimeout(dockerImageCheckerTask, new DockerContext(stack, dockerApiClient), POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
        String cmd[] = { "--debug", "add", "--join", String.format("consul://%s:8500", stack.getAmbariIp()), prepareNewHostAddressJoin(stackId, hostMetadata)};
        new MunchausenBootstrap(dockerApiClient, gatewayData.getPrivateIp(), cmd).call();
    }

    private String prepareNewHostAddressJoin(Long stackId, List<HostMetadata> hostMetadata) throws CloudbreakException {
        StringBuilder sb = new StringBuilder();
        for (HostMetadata metadata : hostMetadata) {
            try {
                InstanceMetaData instanceMetaData = instanceMetaDataRepository.findHostInStack(stackId, metadata.getHostName());
                sb.append(String.format("%s:2376,", instanceMetaData.getPrivateIp()));
            } catch (Exception ex) {
                throw new CloudbreakException(ex);
            }
        }
        return sb.toString().substring(0, sb.toString().length() - 1);
    }

    @Override
    public void gatewaySetup(Long stackId, InstanceGroup gateway) throws CloudbreakException {
        try {
            Stack stack = stackRepository.findOneWithLists(stackId);
            InstanceMetaData gatewayData = gateway.getInstanceMetaData().iterator().next();
            DockerClient swarmManagerClient = DockerClientBuilder.getInstance(getSwarmClientConfig(stack.getAmbariIp())).build();
            swarmInfoPollingService.pollWithTimeout(swarmCheckerTask, new SwarmContext(stack, swarmManagerClient, stack.getFullNodeCount()),
                    POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
            new RegistratorBootstrap(swarmManagerClient, gatewayData.getLongName().split("\\.")[0], gatewayData.getPrivateIp()).call();
            new ConsulWatchBootstrap(swarmManagerClient, gatewayData.getLongName().split("\\.")[0], gatewayData.getPrivateIp(), gatewayData.getId()).call();
            String databaseIp = new AmbariServerDatabaseBootstrap(swarmManagerClient).call();
            new AmbariServerBootstrap(swarmManagerClient, gatewayData.getPrivateIp(), databaseIp, ambariDockerTag).call();
        } catch (Exception ex) {
            throw new CloudbreakException(ex);
        }
    }

    @Override
    public void hostgroupsSetup(Long stackId, Set<InstanceGroup> instanceGroups) throws CloudbreakException {
        try {
            Stack stack = stackRepository.findOneWithLists(stackId);
            ExecutorService executorService = Executors.newFixedThreadPool(TEN);
            List<Future<Boolean>> futures = new ArrayList<>();
            DockerClient swarmManagerClient = DockerClientBuilder.getInstance(getSwarmClientConfig(stack.getAmbariIp())).build();
            swarmInfoPollingService.pollWithTimeout(swarmCheckerTask, new SwarmContext(stack, swarmManagerClient, stack.getFullNodeCount()),
                    POLLING_INTERVAL, MAX_POLLING_ATTEMPTS);
            for (InstanceGroup instanceGroup : instanceGroups) {
                for (InstanceMetaData data : instanceGroup.getInstanceMetaData()) {
                    futures.add(executorService.submit(
                            new ConsulWatchBootstrap(swarmManagerClient, data.getLongName().split("\\.")[0], data.getPrivateIp(), data.getId())));
                }
            }
            for (Future<Boolean> future : futures) {
                future.get();
            }
            futures = new ArrayList<>();
            for (InstanceGroup instanceGroup : instanceGroups) {
                for (InstanceMetaData data : instanceGroup.getInstanceMetaData()) {
                    AmbariAgentBootstrap agentCreate =
                            new AmbariAgentBootstrap(swarmManagerClient, data.getPrivateIp(), data.getLongName().split("\\.")[0], ambariDockerTag,
                                    instanceGroup.getTemplate().getVolumeCount(), data.getId());
                    futures.add(executorService.submit(agentCreate));
                }
            }
            for (Future<Boolean> future : futures) {
                future.get();
            }
        } catch (Exception ex) {
            throw new CloudbreakException(ex);
        }
    }

    @Override
    public FlowContext postSetup(Long stackId) throws CloudbreakException {
        Stack stack = stackRepository.findOneWithLists(stackId);
        return new ProvisioningContext.Builder()
                .setAmbariIp(stack.getAmbariIp())
                .setDefaultParams(stackId, stack.cloudPlatform())
                .build();
    }

    @Override
    public ClusterSetupTool clusterSetupTool() {
        return SWARM;
    }

    private DockerClientConfig getSwarmClientConfig(String ip) {
        return DockerClientConfig.createDefaultConfigBuilder()
                .withVersion("1.16")
                .withUri("http://" + ip + ":3376")
                .build();
    }

    private DockerClientConfig getDockerClientConfig(String ip) {
        return DockerClientConfig.createDefaultConfigBuilder()
                .withVersion("1.16")
                .withUri("http://" + ip + ":2376")
                .build();
    }

    private String getConsulJoinIps(Set<InstanceGroup> instanceGroups) {
        String result = "";
        for (InstanceGroup instanceGroup : instanceGroups) {
            for (InstanceMetaData instanceMetaData : instanceGroup.getInstanceMetaData()) {
                result += instanceMetaData.getPrivateIp() + ":2376,";
            }
        }
        return result.substring(0, result.length() - 1);
    }

    private String getConsulServers(InstanceMetaData gateWayTypeGroup, Set<InstanceGroup> hostGroupTypeGroups, int consulServerCount) {
        String result = "";
        int collected = 0;
        result += gateWayTypeGroup.getPrivateIp() + ",";
        if (collected != consulServerCount) {
            for (InstanceGroup instanceGroup : hostGroupTypeGroups) {
                for (InstanceMetaData instanceMetaData : instanceGroup.getInstanceMetaData()) {
                    result += instanceMetaData.getPrivateIp() + ",";
                    collected++;
                    if (collected == consulServerCount) {
                        break;
                    }
                }
                if (collected == consulServerCount) {
                    break;
                }
            }
        }
        return result.substring(0, result.length() - 1);
    }


}
