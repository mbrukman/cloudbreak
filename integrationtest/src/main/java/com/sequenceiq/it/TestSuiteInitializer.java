package com.sequenceiq.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.ConfigFileApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.springframework.util.StringUtils;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

import com.sequenceiq.it.config.IntegrationTestConfiguration;
import com.sequenceiq.it.util.RestUtil;

@ContextConfiguration(classes = IntegrationTestConfiguration.class, initializers = ConfigFileApplicationContextInitializer.class)
public class TestSuiteInitializer extends AbstractTestNGSpringContextTests {
    @Value("${integrationtest.uaa.server}")
    private String defaultUaaServer;

    @Value("${integrationtest.uaa.user}")
    private String defaultUaaUser;

    @Value("${integrationtest.uaa.password}")
    private String defaultUaaPassword;

    @Value("${integrationtest.keystore.file}")
    private String keystore;

    @Value("${integrationtest.keystore.password}")
    private String keystorePassword;

    @Autowired
    private SuiteContext suiteContext;
    private IntegrationTestContext itContext;

    @BeforeSuite
    public void initSuiteMap(ITestContext testContext) throws Exception {
        // Workaround of https://jira.spring.io/browse/SPR-4072
        springTestContextBeforeTestClass();
        springTestContextPrepareTestInstance();

        suiteContext.putItContext(testContext.getSuite().getName(), new IntegrationTestContext());
        itContext = suiteContext.getItContext(testContext.getSuite().getName());
    }

    @BeforeSuite(dependsOnMethods = "initSuiteMap", groups = "suiteInit")
    @Parameters({ "uaaServer", "uaaUser", "uaaPassword" })
    public void initTestSuite(@Optional("") String uaaServer, @Optional("") String uaaUser, @Optional("") String uaaPassword) throws Exception {
/*        if (StringUtils.hasLength(keystore)) {
            RestAssured.keystore(keystore, keystorePassword);
        }*/
        uaaServer = getString(uaaServer, defaultUaaServer);
        uaaUser = getString(uaaUser, defaultUaaUser);
        uaaPassword = getString(uaaPassword, defaultUaaPassword);

        String accessToken = RestUtil.getToken(uaaServer, uaaUser, uaaPassword);
        Assert.assertNotNull(accessToken, "Access token cannot be null.");
        itContext.putContextParam(IntegrationTestContext.AUTH_TOKEN, accessToken);
    }

    private String getString(String paramValue, String defaultValue) {
        return StringUtils.hasLength(paramValue) ? paramValue : defaultValue;
    }
}
