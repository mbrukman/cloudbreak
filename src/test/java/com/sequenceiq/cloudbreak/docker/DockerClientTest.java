package com.sequenceiq.cloudbreak.docker;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
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

    public static final int nodeCount = 5;
    public static final int serverCount = 3;

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

    @Test
    public void testBouncyCastle() {
        RSAKeyPairGenerator rsaKeyPairGenerator = new RSAKeyPairGenerator();
        rsaKeyPairGenerator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(3), new SecureRandom(), 2048, 80));
        AsymmetricCipherKeyPair keypair = rsaKeyPairGenerator.generateKeyPair();

    }

    @Test
    public void testParallelBootstrap() {
        String publicIp = "54.153.87.183";
        String swarmUri = "https://" + publicIp + ":4376";

        DockerClientConfig config = DockerClientConfig.createDefaultConfigBuilder()
                .withVersion("1.16")
                .withUri(swarmUri)
                .withDockerCertPath("/Users/marci/prj/bootstrap-poc/keys")
                .build();
        DockerClient docker = DockerClientBuilder.getInstance(config).build();

        List<InspectContainerResponse.Node> nodes = new ArrayList<>();
        List<String> consulServerIps = new ArrayList<>();

        // start consul agents
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<InspectContainerResponse.Node>> consulFutures = new ArrayList<Future<InspectContainerResponse.Node>>();
        InspectContainerResponse.Node firstNode = startConsulContainer(docker, ConsulNode.FIRST, null, "0");
        nodes.add(firstNode);
        for (int i = 1; i < nodeCount; i++) {
            consulFutures.add(executor.submit(new ConsulBootstrap(i, docker, firstNode.getIp())));
        }

        for (Future<InspectContainerResponse.Node> future : consulFutures) {
            try {
                nodes.add(future.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        List<Future<Boolean>> swarmAgentFutures = new ArrayList<Future<Boolean>>();
        for (InspectContainerResponse.Node node : nodes) {
            swarmAgentFutures.add(executor.submit(new SwarmAgentBootstrap(docker, node, firstNode.getIp())));
        }

        for (Future<Boolean> future : swarmAgentFutures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        List<Future<Boolean>> swarmManagerFutures = new ArrayList<Future<Boolean>>();
        for (InspectContainerResponse.Node node : nodes) {
            swarmManagerFutures.add(executor.submit(new SwarmManagerBootstrap(docker, node, firstNode.getIp())));
        }

        for (Future<Boolean> future : swarmManagerFutures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        List<Future<Boolean>> registratorFutures = new ArrayList<Future<Boolean>>();
        for (InspectContainerResponse.Node node : nodes) {
            registratorFutures.add(executor.submit(new RegistratorBootstrap(docker, node)));
        }

        for (Future<Boolean> future : registratorFutures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        new AmbariServerBootstrap(docker, nodes.get(0)).call();

        List<Future<Boolean>> ambariAgentFutures = new ArrayList<Future<Boolean>>();
        for (InspectContainerResponse.Node node : nodes) {
            ambariAgentFutures.add(executor.submit(new AmbariAgentBootstrap(docker, node)));
        }

        for (Future<Boolean> future : ambariAgentFutures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Happy times!");


    }

    private class ConsulBootstrap implements Callable<InspectContainerResponse.Node> {

        private int i;
        private DockerClient docker;
        private String joinIp;

        public ConsulBootstrap(int i, DockerClient docker, String joinIp) {
            this.i = i;
            this.docker = docker;
            this.joinIp = joinIp;
        }

        @Override
        public InspectContainerResponse.Node call() throws Exception {
            ConsulNode type = i < 3 ? ConsulNode.SERVER : ConsulNode.AGENT;
            return startConsulContainer(docker, type, joinIp, String.valueOf(i));
        }
    }

    private class SwarmAgentBootstrap implements Callable<Boolean> {

        private DockerClient docker;
        private InspectContainerResponse.Node node;
        private String consulIp;

        public SwarmAgentBootstrap(DockerClient docker, InspectContainerResponse.Node node, String consulIp) {
            this.docker = docker;
            this.node = node;
            this.consulIp = consulIp;
        }

        @Override
        public Boolean call() throws Exception {
            CreateContainerResponse response = docker.createContainerCmd("swarm")
                    .withEnv(String.format("constraint:node==%s", node.getName()))
                    .withCmd("join",
                            String.format("--addr=%s", node.getAddr()),
                            String.format("consul://%s:8500/swarm", consulIp))
                    .exec();
            docker.startContainerCmd(response.getId()).exec();
            return true;
        }
    }

    private class SwarmManagerBootstrap implements Callable<Boolean> {

        private DockerClient docker;
        private InspectContainerResponse.Node node;
        private String consulIp;

        public SwarmManagerBootstrap(DockerClient docker, InspectContainerResponse.Node node, String consulIp) {
            this.docker = docker;
            this.node = node;
            this.consulIp = consulIp;
        }

        @Override
        public Boolean call() throws Exception {
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
                            String.format("consul://%s:8500/swarm", consulIp))
                    .exec();
            docker.startContainerCmd(response.getId())
                    .withBinds(new Bind("/etc/docker/keys", new Volume("/certs")))
                    .withPortBindings(new PortBinding(new Ports.Binding("0.0.0.0", 3376), new ExposedPort(3376)))
                    .exec();
            return true;
        }
    }

    private class RegistratorBootstrap implements Callable<Boolean> {

        private DockerClient docker;
        private InspectContainerResponse.Node node;

        public RegistratorBootstrap(DockerClient docker, InspectContainerResponse.Node node) {
            this.docker = docker;
            this.node = node;
        }

        @Override
        public Boolean call() throws Exception {
            HostConfig hostConfig = new HostConfig();
            hostConfig.setPrivileged(true);

            CreateContainerResponse response = docker.createContainerCmd("sequenceiq/registrator:v5.1")
                    .withEnv(String.format("constraint:node==%s", node.getName()))
                    .withHostConfig(hostConfig)
                    .withCmd(String.format("consul://%s:8500", node.getIp()))
                    .exec();
            docker.startContainerCmd(response.getId())
                    .withBinds(new Bind("/var/run/docker.sock", new Volume("/tmp/docker.sock")))
                    .exec();
            return true;
        }
    }

    private class AmbariServerBootstrap {

        private DockerClient docker;
        private InspectContainerResponse.Node node;

        public AmbariServerBootstrap(DockerClient docker, InspectContainerResponse.Node node) {
            this.docker = docker;
            this.node = node;
        }

        public Boolean call() {
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
            return true;
        }
    }

    private class AmbariAgentBootstrap implements Callable<Boolean> {

        private DockerClient docker;
        private InspectContainerResponse.Node node;

        public AmbariAgentBootstrap(DockerClient docker, InspectContainerResponse.Node node) {
            this.docker = docker;
            this.node = node;
        }

        @Override
        public Boolean call() throws Exception {
            HostConfig hostConfig = new HostConfig();
            hostConfig.setNetworkMode("host");
            hostConfig.setRestartPolicy(RestartPolicy.alwaysRestart());

            CreateContainerResponse response = docker.createContainerCmd("sequenceiq/ambari:1.7.0-consul")
                    .withHostConfig(hostConfig)
                    .withEnv(String.format("constraint:node==%s", node.getName()),
                            String.format("BRIDGE_IP=%s", node.getIp()),
                            "HADOOP_CLASSPATH=/data/jars/*:/usr/lib/hadoop/lib/*")
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
            return true;
        }
    }

    private enum ConsulNode {
        FIRST, SERVER, AGENT
    }

    private InspectContainerResponse.Node startConsulContainer(DockerClient docker, ConsulNode type, String joinIp, String postfix) {
        HostConfig hostConfig = new HostConfig();
        hostConfig.setNetworkMode("host");
        hostConfig.setRestartPolicy(RestartPolicy.alwaysRestart());
        hostConfig.setPortBindings(new Ports());

        Ports ports = new Ports();
        ports.add(new PortBinding(new Ports.Binding("0.0.0.0", 8400), new ExposedPort(8400)));
        ports.add(new PortBinding(new Ports.Binding("0.0.0.0", 8500), new ExposedPort(8500)));

        hostConfig.setPortBindings(ports);

        String consulCmd;
        if (ConsulNode.FIRST.equals(type)) {
            consulCmd = String.format("-server -bootstrap-expect %s", serverCount);
        } else if (ConsulNode.SERVER.equals(type)) {
            consulCmd = String.format("-server -bootstrap-expect %s -retry-join %s", serverCount, joinIp);
        } else {
            consulCmd = String.format("-retry-join %s", joinIp);
        }

        CreateContainerResponse response = docker.createContainerCmd("sequenceiq/consul:v0.4.1.ptr")
                .withHostConfig(hostConfig)
                .withExposedPorts(new ExposedPort(8400), new ExposedPort(8500))
                .withName("consul-" + postfix)
                .withCmd(consulCmd)
                .exec();

        docker.startContainerCmd(response.getId())
                .exec();

        return docker.inspectContainerCmd(response.getId()).exec().getNode();
    }
}
