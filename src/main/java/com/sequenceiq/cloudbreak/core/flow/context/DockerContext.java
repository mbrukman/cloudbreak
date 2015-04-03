package com.sequenceiq.cloudbreak.core.flow.context;

import com.github.dockerjava.api.DockerClient;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.service.StackDependentPollerObject;

public class DockerContext extends StackDependentPollerObject {

    private DockerClient dockerClient;

    public DockerContext(Stack stack, DockerClient dockerClient) {
        super(stack);
        this.dockerClient = dockerClient;
    }

    public DockerClient getDockerClient() {
        return dockerClient;
    }
}
