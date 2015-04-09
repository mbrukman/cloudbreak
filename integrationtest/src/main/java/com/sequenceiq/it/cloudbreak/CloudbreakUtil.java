package com.sequenceiq.it.cloudbreak;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.testng.Assert;

import com.jayway.restassured.path.json.JsonPath;
import com.jayway.restassured.response.Response;
import com.sequenceiq.ambari.client.AmbariClient;
import com.sequenceiq.cloudbreak.client.CloudbreakClient;
import com.sequenceiq.it.IntegrationTestContext;
import com.sequenceiq.it.util.RestUtil;

public class CloudbreakUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudbreakUtil.class);
    private static final int MAX_RETRY = 180;
    private static final int POLLING_INTERVAL = 10000;

    private CloudbreakUtil() {
    }

    public static void waitForStackStatus(IntegrationTestContext itContext, String stackId, String desiredStatus) {
        waitForStackStatus(itContext, stackId, desiredStatus, "status");
    }

    public static void waitForStackStatus(IntegrationTestContext itContext, String stackId, String desiredStatus, String statusPath) {
        WaitResult waitResult = waitForStatus(itContext, stackId, desiredStatus, statusPath);
        if (waitResult == WaitResult.FAILED) {
            Assert.fail("The stack has failed");
        }
        if (waitResult == WaitResult.TIMEOUT) {
            Assert.fail("Timeout happened");
        }
    }

    public static WaitResult waitForStatus(IntegrationTestContext itContext, String stackId, String desiredStatus) {
        return waitForStatus(itContext, stackId, desiredStatus, "status");
    }

    public static WaitResult  waitForStatus(IntegrationTestContext itContext, String stackId, String desiredStatus, String statusPath) {
        WaitResult waitResult = WaitResult.SUCCESSFUL;
        String stackStatus = null;
        int retryCount = 0;
        do {
            LOGGER.info("Waiting for stack status {}, stack id: {}, current status {} ...", desiredStatus, stackId, stackStatus);
            sleep();
            Response response = RestUtil.entityPathRequest(itContext.getContextParam(CloudbreakITContextConstants.CLOUDBREAK_SERVER),
                    itContext.getContextParam(IntegrationTestContext.AUTH_TOKEN), "stackId", stackId).
                    get("stacks/{stackId}/status");
            if (response.getStatusCode() != HttpStatus.OK.value() && response.getStatusCode() != HttpStatus.NOT_FOUND.value()) {
                continue;
            }
            JsonPath stack = response.jsonPath();
            stackStatus = stack.get(statusPath);
            if (stackStatus == null) {
                stackStatus = "DELETE_COMPLETED";
            }
            retryCount++;
        } while (!desiredStatus.equals(stackStatus) && !stackStatus.contains("FAILED") && retryCount < MAX_RETRY);
        LOGGER.info("Status {} for {} is in desired status {}", statusPath, stackId, stackStatus);
        if (stackStatus.contains("FAILED")) {
            waitResult = WaitResult.FAILED;
        }
        if (retryCount == MAX_RETRY) {
            waitResult = WaitResult.TIMEOUT;
        }
        return waitResult;
    }

    public static void checkClusterAvailability(CloudbreakClient client, String stackId) throws Exception {
        Map<String, Object> stack = (Map<String, Object>) client.getStack(stackId);

        Assert.assertEquals("AVAILABLE", ((Map<String, Object>) stack.get("cluster")).get("status"), "The cluster hasn't been started!");
        Assert.assertEquals("AVAILABLE", stack.get("status"), "The stack hasn't been started!");

        String ambariIp = (String) stack.get("ambariServerIp");
        Assert.assertNotNull(ambariIp, "The Ambari IP is not available!");

        AmbariClient ambariClient = new AmbariClient(ambariIp);
        Assert.assertEquals("RUNNING", ambariClient.healthCheck(), "The Ambari server is not running!");
        Assert.assertEquals(ambariClient.getClusterHosts().size(), getNodeCount(stack) - 1,
                "The number of cluster nodes in the stack differs from the number of nodes registered in ambari");
    }

    public static void checkClusterStopped(CloudbreakClient client, String stackId) throws Exception {
        Map<String, Object> stack = (Map<String, Object>) client.getStack(stackId);

        Assert.assertEquals("STOPPED", ((Map<String, Object>) stack.get("cluster")).get("status"), "The cluster is not stopped!");
        Assert.assertEquals("STOPPED", stack.get("status"), "The stack is not stopped!");

        String ambariIp = (String) stack.get("ambariServerIp");
        AmbariClient ambariClient = new AmbariClient(ambariIp);
        Assert.assertFalse(isAmbariRunning(ambariClient), "The Ambari server is running in stopped state!");
    }

    public static boolean isAmbariRunning(AmbariClient ambariClient) {
        try {
            String ambariHealth = ambariClient.healthCheck();
            if ("RUNNING".equals(ambariHealth)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLLING_INTERVAL);
        } catch (InterruptedException e) {
            LOGGER.warn("Ex during wait: {}", e);
        }
    }

    private static int getNodeCount(Map<String, Object> stackResponse) {
        List<Map<String, Object>> instanceGroups = (List<Map<String, Object>>) stackResponse.get("instanceGroups");
        int nodeCount = 0;
        for (Map<String, Object> instanceGroup : instanceGroups) {
            nodeCount += (Integer) instanceGroup.get("nodeCount");
        }
        return nodeCount;
    }
}
