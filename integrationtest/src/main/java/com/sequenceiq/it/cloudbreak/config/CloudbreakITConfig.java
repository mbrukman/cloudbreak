package com.sequenceiq.it.cloudbreak.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sequenceiq.it.cloudbreak.TemplateAdditionHelper;

@Configuration
public class CloudbreakITConfig {
    @Bean TemplateAdditionHelper templateAdditionHelper() {
        return new TemplateAdditionHelper();
    }
}
