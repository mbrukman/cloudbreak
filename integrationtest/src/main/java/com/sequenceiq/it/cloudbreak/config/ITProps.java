package com.sequenceiq.it.cloudbreak.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integrationtest")
public class ITProps {
    private String integrationTestName;
    private String name;
    private String credentialGcpName;

    public void setName(String name) {
        this.name = name;
    }

    public void setCredentialGcpName(String credentialGcpName) {
        this.credentialGcpName = credentialGcpName;
    }

    public String getCredentialGcpName() {
        return credentialGcpName;
    }
}
