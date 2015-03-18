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
            String publicIp = "54.153.77.181";
            String swarmUri = "https://" + publicIp + ":4376";
            int nodeCount = 5;
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
            for (int i = 0; i < nodeCount; i++) {
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
                hostConfig.setPortBindings(new Ports(new ExposedPort(3376), new Ports.Binding(3376)));

                CreateContainerResponse response = docker.createContainerCmd("swarm")
                        .withHostConfig(hostConfig)
                        .withExposedPorts(new ExposedPort(3376))
                        .withCmd("--debug",
                                "manage",
                                "-H",
                                "tcp://0.0.0.0:3376",
                                "--tlsverify",
                                "--tlscacert=/certs/ca.pem",
                                "--tlscert=/certs/server.pem",
                                "--tlskey=/certs/server-key.pem",
                                String.format("consul://%s:8500/swarm", consulServerIps.get(0)))
                        .exec();
                docker.startContainerCmd(response.getId())
                        .withBinds(new Bind("/etc/docker/keys", new Volume("/certs")))
                        .withPortBindings(new PortBinding(new Ports.Binding("0.0.0.0", 3376), new ExposedPort(3376)))
                        .exec();
            }

            String consulSwarmUri = "https://" + publicIp + ":3376";
            DockerClientConfig newConfig = DockerClientConfig.createDefaultConfigBuilder()
                    .withVersion("1.16")
                    .withUri(consulSwarmUri)
                    .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys")
                    .build();
            docker = DockerClientBuilder.getInstance(newConfig).build();

            // start registrators
            //docker run -d --name=registrator --privileged -v /var/run/docker.sock:/tmp/docker.sock gliderlabs/registrator:v5 consul://${BRIDGE_IP}:8500
            for (int i = 0; i < nodes.size(); i++) {
                InspectContainerResponse.Node node = nodes.get(i);

                HostConfig hostConfig = new HostConfig();
                hostConfig.setPrivileged(true);

                CreateContainerResponse response = docker.createContainerCmd("sequenceiq/registrator:v5.1")
                        .withEnv(String.format("constraint:node==%s", node.getName()))
                        .withHostConfig(hostConfig)
                        .withName("registrator" + i)
                        .withCmd(String.format("consul://%s:8500", node.getIp()))
                        .exec();
                docker.startContainerCmd(response.getId())
                        .withBinds(new Bind("/var/run/docker.sock", new Volume("/tmp/docker.sock")))
                        .exec();
            }

            // start ambari agents + server
            for (int i = 0; i < nodes.size(); i++) {
                //    docker run -d --name ambari-server --net=host --restart=always -e BRIDGE_IP=$(get_ip) sequenceiq/ambari:1.7.0-consul /start-server
                InspectContainerResponse.Node node = nodes.get(i);
                if (i == 0) {
                    HostConfig hostConfig = new HostConfig();
                    hostConfig.setNetworkMode("host");
                    hostConfig.setRestartPolicy(RestartPolicy.alwaysRestart());
                    Ports ports = new Ports();
                    ports.add(new PortBinding(new Ports.Binding(8080), new ExposedPort(8080)));
                    hostConfig.setPortBindings(ports);

                    CreateContainerResponse response = docker.createContainerCmd("sequenceiq/ambari:1.7.0-consul")
                            .withHostConfig(hostConfig)
                            .withExposedPorts(new ExposedPort(8080))
                            .withEnv(
                                    String.format("constraint:node==%s", node.getName()),
                                    String.format("BRIDGE_IP=%s", node.getIp()),
                                    "SERVICE_NAME=ambari-8080")
                            .withName("ambari-server")
                            .withCmd("/start-server")
                            .exec();
                    docker.startContainerCmd(response.getId())
                            .withPortBindings(new PortBinding(new Ports.Binding("0.0.0.0", 8080), new ExposedPort(8080)))
                            .withNetworkMode("host")
                            .withRestartPolicy(RestartPolicy.alwaysRestart())
                            .exec();
                }

                //start ambari agent
                HostConfig hostConfig = new HostConfig();
                hostConfig.setNetworkMode("host");
                hostConfig.setRestartPolicy(RestartPolicy.alwaysRestart());

                CreateContainerResponse response = docker.createContainerCmd("sequenceiq/ambari:1.7.0-consul")
                        .withHostConfig(hostConfig)
                        .withEnv(String.format("constraint:node==%s", node.getName()),
                                String.format("BRIDGE_IP=%s", node.getIp()),
                                "HADOOP_CLASSPATH=/data/jars/*:/usr/lib/hadoop/lib/*")
                        .withName("ambari-agent-" + i)
                        .withCmd("/start-agent")
                        .exec();
                List<Bind> binds = Arrays.asList(
                        new Bind("/usr/local/public_host_script.sh", new Volume("/etc/ambari-agent/conf/public-hostname.sh")),
                        new Bind("/data/jars", new Volume("/data/jars")));
                // Get the number of volumes from the corresponding instancegroup (based on node.getIp())
                int volumeCount = 0;
                for (int j = 0; j < volumeCount; j++) {
                    binds.add(new Bind("/mnt/fs" + j, new Volume("/mnt/fs" + j)));
                }

                docker.startContainerCmd(response.getId())
                        .withNetworkMode("host")
                        .withRestartPolicy(RestartPolicy.alwaysRestart())
                        .withBinds(binds.toArray(new Bind[binds.size()]))
                        .exec();

            }
//            docker.removeContainerCmd("id").withForce(true).exec();

            System.out.println("happy times");
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    @Test
    public void testDockerInfo() {
        try {
            String swarmUri = "https://54.153.77.181:3376";

            DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
                    .withVersion("1.16")
                    .withUri(swarmUri)
                    .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys2")
                    .build();
            DockerClient docker = DockerClientBuilder.getInstance(config).build();

            InspectContainerResponse response = docker.inspectContainerCmd("05db194f3fa0").exec();
            System.out.println(response.getNode());
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
