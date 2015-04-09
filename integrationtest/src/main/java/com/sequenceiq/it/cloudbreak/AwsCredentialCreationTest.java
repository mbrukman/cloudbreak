package com.sequenceiq.it.cloudbreak;

import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import com.sequenceiq.it.util.ResourceUtil;

public class AwsCredentialCreationTest extends AbstractCloudbreakIntegrationTest {
    private static final String CREDENTIAL_NAME = "it-aws-cred";

    @Test
    @Parameters({ "credentialName", "roleArn", "publicKeyFile" })
    public void testAwsCredentialCreation(@Optional(CREDENTIAL_NAME) String credentialName, String roleArn,
            String publicKeyFile) throws Exception {
        // GIVEN
        String publicKey = ResourceUtil.readStringFromResource(applicationContext, publicKeyFile).replaceAll("\n", "");
        // WHEN
        // TODO publicInAccount
        String id = getClient().postEc2Credential(credentialName, "Test AWS credential for integration testing", roleArn, publicKey, false);
        // THEN
        Assert.assertNotNull(id);
        getItContext().putContextParam(CloudbreakITContextConstants.CREDENTIAL_ID, id, true);
    }
}
