package com.sequenceiq.cloudbreak.controller.json;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReinstallJson {

    private Long blueprintId;
    private Set<HostGroupJson> hostgroups;

    public ReinstallJson() {

    }

    public Long getBlueprintId() {
        return blueprintId;
    }

    public Set<HostGroupJson> getHostgroups() {
        return hostgroups;
    }

    public void setBlueprintId(Long blueprintId) {
        this.blueprintId = blueprintId;
    }

    public void setHostgroups(Set<HostGroupJson> hostgroups) {
        this.hostgroups = hostgroups;
    }
}
