package com.sequenceiq.cloudbreak.core.flow;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.model.Image;
import com.sequenceiq.cloudbreak.controller.InternalServerException;
import com.sequenceiq.cloudbreak.core.flow.context.DockerContext;
import com.sequenceiq.cloudbreak.service.StackBasedStatusCheckerTask;
import com.sequenceiq.cloudbreak.service.cluster.flow.DockerContainer;

@Component
public class DockerImageCheckerTask extends StackBasedStatusCheckerTask<DockerContext> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerImageCheckerTask.class);

    @Override
    public boolean checkStatus(DockerContext dockerContext) {
        LOGGER.info("Checking docker image if available.");
        try {
            List<Image> exec = dockerContext.getDockerClient().listImagesCmd().exec();
            for (Image image : exec) {
                if (image.getRepoTags()[0].equals(DockerContainer.REGISTRATOR.getContainer().get())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void handleTimeout(DockerContext t) {
        throw new InternalServerException(String.format("Operation timed out. Could not reach docker in time."));
    }

    @Override
    public String successMessage(DockerContext t) {
        return String.format("Docker available successfully ");
    }
}
