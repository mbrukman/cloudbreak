package com.sequenceiq.cloudbreak.core.flow.containers;

import static com.sequenceiq.cloudbreak.service.cluster.flow.DockerContainer.MUNCHAUSEN;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

public class MunchausenBootstrap {

    private final DockerClient docker;
    private final String privateIp;
    private final String cmd[];

    public MunchausenBootstrap(DockerClient docker, String privateIp, String cmd[]) {
        this.docker = docker;
        this.privateIp = privateIp;
        this.cmd = cmd;
    }

    public Boolean call() {
        HostConfig hostConfig = new HostConfig();
        hostConfig.setPrivileged(true);
        CreateContainerResponse response = docker.createContainerCmd(MUNCHAUSEN.getContainer().get())
                .withEnv(String.format("BRIDGE_IP=%s", privateIp))
                .withName(MUNCHAUSEN.getName())
                .withHostConfig(hostConfig)
                .withCmd(cmd)
                .exec();
        docker.startContainerCmd(response.getId())
                .withBinds(new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock")))
                .exec();
        return true;
    }
}
