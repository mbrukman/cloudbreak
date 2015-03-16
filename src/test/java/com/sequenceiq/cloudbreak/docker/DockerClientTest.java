package com.sequenceiq.cloudbreak.docker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;

public class DockerClientTest {

    @Test
    public void testSimpleDockerCommands() {
        try {
            String swarmUri = "https://54.153.24.86:3376";
            List<String> privateIps = Arrays.asList("172.31.13.215", "172.31.13.216", "172.31.13.214");
            int serverCount = 3;

            DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
                    .withVersion("1.16")
                    .withUri(swarmUri)
                    .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys")
                    .build();
            DockerClient docker = DockerClientBuilder.getInstance(config).build();

            List<InspectContainerResponse.Node> nodes = new ArrayList<>();
            List<String> consulServerIps = new ArrayList<>();

            // start consul agents
            for (int i = 0; i < privateIps.size(); i++) {
                HostConfig hostConfig = new HostConfig();
                hostConfig.setNetworkMode("host");
                hostConfig.setRestartPolicy(RestartPolicy.alwaysRestart());
                hostConfig.setPortBindings(new Ports());

                Ports ports = new Ports();
                ports.add(new PortBinding(new Ports.Binding("0.0.0.0", 8400), new ExposedPort(8400)));
                ports.add(new PortBinding(new Ports.Binding("0.0.0.0", 8500), new ExposedPort(8500)));

                hostConfig.setPortBindings(ports);

                String consulCmd;
                if (i == 0) {
                    consulCmd = String.format("-server -bootstrap-expect %s", serverCount);
                } else if (i < serverCount) {
                    consulCmd = String.format("-server -bootstrap-expect %s -retry-join %s", serverCount, consulServerIps.get(0));
                } else {
                    consulCmd = String.format("-retry-join %s", consulServerIps.get(0));
                }

                CreateContainerResponse response = docker.createContainerCmd("sequenceiq/consul:v0.4.1.ptr")
                        .withHostConfig(hostConfig)
                        .withExposedPorts(new ExposedPort(8400), new ExposedPort(8500))
                        .withName("consul-" + i)
                        .withCmd(consulCmd)
                        .exec();

                docker.startContainerCmd(response.getId())
                        .exec();

                InspectContainerResponse inspectResponse = docker.inspectContainerCmd(response.getId()).exec();
                nodes.add(inspectResponse.getNode());
                if (i < 3) {
                    consulServerIps.add(inspectResponse.getNode().getIp());
                }
            }

            //should we wait until every node is joined?

            // start swarm agents with consul
            for (InspectContainerResponse.Node node : nodes) {
                CreateContainerResponse response = docker.createContainerCmd("swarm")
                        .withEnv(String.format("constraint:node==%s", node.getName()))
                        .withCmd("join",
                                String.format("--addr=%s", node.getAddr()),
                                String.format("consul://%s:8500/swarm", consulServerIps.get(0)))
                        .exec();
                docker.startContainerCmd(response.getId()).exec();
            }

            // start new swarm managers with consul
            //docker run -d -v /etc/docker/keys:/certs -p 3376:3376 swarm manage -H tcp://0.0.0.0:3376 --tlsverify --tlscacert=/certs/ca.pem --tlscert=/certs/server.pem --tlskey=/certs/server-key.pem consul://172.31.13.215:8500/swarm
            for (InspectContainerResponse.Node node : nodes) {
                HostConfig hostConfig = new HostConfig();
                hostConfig.setPortBindings(new Ports(new ExposedPort(4376), new Ports.Binding(4376)));

                Volume certVolume = new Volume("/certs");
                CreateContainerResponse response = docker.createContainerCmd("swarm")
                        .withHostConfig(hostConfig)
                        .withExposedPorts(new ExposedPort(4376))
                        .withCmd("--debug",
                                "manage",
                                "-H",
                                "tcp://0.0.0.0:4376",
                                "--tlsverify",
                                "--tlscacert=/certs/ca.pem",
                                "--tlscert=/certs/server.pem",
                                "--tlskey=/certs/server-key.pem",
                                String.format("consul://%s:8500/swarm", consulServerIps.get(0)))
                        .exec();
                docker.startContainerCmd(response.getId())
                        .withBinds(new Bind("/etc/docker/keys", certVolume))
                        .withPortBindings(new PortBinding(new Ports.Binding("0.0.0.0", 4376), new ExposedPort(4376)))
                        .exec();
            }

//            String consulSwarmUri = "https://54.153.24.86:3376";
//            DockerClientConfig newConfig = DockerClientConfig.createDefaultConfigBuilder()
//                    .withVersion("1.16")
//                    .withUri(consulSwarmUri)
//                    .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys")
//                    .build();
//            docker = DockerClientBuilder.getInstance(newConfig).build();
//
//            docker.removeContainerCmd("id").withForce(true).exec();

            System.out.println("happy times");
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    @Test
    public void testDockerInfo() {
        try {
            String swarmUri = "https://54.153.24.86:2376";

            DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
                    .withVersion("1.16")
                    .withUri(swarmUri)
                    .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys")
                    .build();
            DockerClient docker = DockerClientBuilder.getInstance(config).build();

            InspectContainerResponse response = docker.inspectContainerCmd("2eb1861297c7").exec();
            System.out.println(response.getNode());
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
