package com.sequenceiq.cloudbreak.service.cluster.flow;

import com.google.common.base.Optional;

public enum DockerContainer {

    AMBARI_SERVER("ambari-server", Optional.<String>absent()),
    AMBARI_AGENT("ambari-agent", Optional.<String>absent()),
    AMBARI_DB("ambari_db", Optional.of("postgres:9.4.1")),
    REGISTRATOR("registrator", Optional.of("sequenceiq/registrator:v5.1")),
    MUNCHAUSEN("munchausen", Optional.of("sequenceiq/munchausen:upscale")),
    CONSUL_WATCH("consul-watch", Optional.of("sequenceiq/docker-consul-watch-plugn:1.7.0-consul"));

    private final String name;
    private final Optional<String> container;

    private DockerContainer(String name, Optional<String> container) {
        this.name = name;
        this.container = container;
    }

    public String getName() {
        return name;
    }

    public Optional<String> getContainer() {
        return container;
    }
}
