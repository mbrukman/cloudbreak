package com.sequenceiq.it.cloudbreak.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integrationtest")
public class ITProps {
    private Map<String, String> credentialNames;

    public void setCredentialNames(Map<String, String> credentialNames) {
        this.credentialNames = credentialNames;
    }

    public Map<String, String> getCredentialNames() {
        return credentialNames;
    }

    public String getCredentialName(String cloudProvider) {
        return credentialNames.get(cloudProvider);
    }
}
