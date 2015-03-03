package com.sequenceiq.cloudbreak.service.cluster.flow;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.sequenceiq.cloudbreak.domain.InstanceMetaData;

public interface PluginManager {

    void prepareKeyValues(Collection<InstanceMetaData> instanceMetaData, Map<String, String> keyValues);

    Set<String> installPlugins(Collection<InstanceMetaData> instanceMetaData, Collection<String> plugins);

    Set<String> triggerPlugins(Collection<InstanceMetaData> instanceMetaData, ConsulPluginEvent event);

    void triggerConsulEvent(Long stackId, Collection<InstanceMetaData> instanceMetaDatas, ConsulPluginEvent event);

    void waitForEventFinish(Long stackId, Collection<InstanceMetaData> instanceMetaData, Set<String> eventIds);
}
