package com.sequenceiq.it.cloudbreak;

import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import com.sequenceiq.it.util.ResourceUtil;

public class GccCredentialCreationTest extends AbstractCloudbreakIntegrationTest {
    @Test
    @Parameters({ "name", "projectId", "serviceAccountId", "serviceAccountPrivateKeyP12File", "publicKeyFile" })
    public void testGCCCredentialCreation(String name, String projectId, String serviceAccountId,
            String serviceAccountPrivateKeyP12File, String publicKeyFile) throws Exception {
        // GIVEN
        String serviceAccountPrivateKey = ResourceUtil.readBase64EncodedContentFromResource(applicationContext, serviceAccountPrivateKeyP12File);
        String publicKey = ResourceUtil.readStringFromResource(applicationContext, publicKeyFile).replaceAll("\n", "");
        // WHEN
        // TODO publicInAccount
        String id = getClient().postGccCredential(name, "GCC credential for integartiontest", publicKey, false, projectId, serviceAccountId,
                serviceAccountPrivateKey);
        // THEN
        Assert.assertNotNull(id);
        getItContext().putContextParam(CloudbreakITContextConstants.CREDENTIAL_ID, id, true);
    }
}
