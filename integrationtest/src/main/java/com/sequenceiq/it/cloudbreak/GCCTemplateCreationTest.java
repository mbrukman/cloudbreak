package com.sequenceiq.it.cloudbreak;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.Response;
import com.sequenceiq.it.IntegrationTestContext;
import com.sequenceiq.it.util.FreeMarkerUtil;
import com.sequenceiq.it.util.RestUtil;

import freemarker.template.Template;

public class GCCTemplateCreationTest extends AbstractCloudbreakIntegrationTest {
    @Autowired
    private TemplateAdditionHelper additionHelper;

    @Autowired
    private Template gccTemplateCreationTemplate;
    private List<TemplateAddition> additions;

    @BeforeMethod
    @Parameters({ "templateAdditions" })
    public void setup(@Optional("master,1;slave_1,3") String templateAdditions) {
        additions = additionHelper.parseTemplateAdditions(templateAdditions);
    }

    @Test
    @Parameters({ "gccName", "gccInstanceType", "volumeType", "volumeCount", "volumeSize" })
    public void testGCCTemplateCreation(@Optional("it-gcc-template") String gccName, @Optional("N1_STANDARD_2") String gccInstanceType,
            @Optional("HDD") String volumeType, @Optional("1") String volumeCount, @Optional("30") String volumeSize) {
        // GIVEN
        IntegrationTestContext itContext = getItContext();
        Map<String, Object> templateModel = new HashMap<>();
        templateModel.put("gccName", gccName);
        templateModel.put("gccInstanceType", gccInstanceType);
        templateModel.put("volumeType", volumeType);
        templateModel.put("volumeCount", volumeCount);
        templateModel.put("volumeSize", volumeSize);
        // WHEN
        Response resourceCreationResponse = RestUtil.createEntityRequest(itContext.getContextParam(CloudbreakITContextConstants.CLOUDBREAK_SERVER),
                itContext.getContextParam(IntegrationTestContext.AUTH_TOKEN), FreeMarkerUtil.renderTemplate(gccTemplateCreationTemplate, templateModel))
                .post("/user/templates");
        // THEN
        checkResponse(resourceCreationResponse, HttpStatus.CREATED, ContentType.JSON);
        additionHelper.handleTemplateAdditions(itContext, resourceCreationResponse.jsonPath().getString("id"), additions);
    }
}
