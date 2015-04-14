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

public class AzureTemplateCreationTest extends AbstractCloudbreakIntegrationTest {
    @Autowired
    private TemplateAdditionHelper templateAdditionHelper;

    @Autowired
    private Template azureTemplateCreationTemplate;
    private List<TemplateAddition> additions;

    @BeforeMethod
    @Parameters({ "templateAdditions" })
    public void setup(@Optional("master,1;slave_1,3") String templateAdditions) {
        additions = templateAdditionHelper.parseTemplateAdditions(templateAdditions);
    }

    @Test
    @Parameters({ "azureTemplateName", "azureVmType", "azureVolumeCount", "azureVolumeSize" })
    public void testAwsTemplateCreation(@Optional("it-azure-template") String azureTemplateName, @Optional("MEDIUM") String azureVmType,
            @Optional("1") String azureVolumeCount, @Optional("10") String azureVolumeSize) {
        // GIVEN
        IntegrationTestContext itContext = getItContext();
        Map<String, Object> templateModel = new HashMap<>();
        templateModel.put("azureTemplateName", azureTemplateName);
        templateModel.put("azureVmType", azureVmType);
        templateModel.put("azureVolumeCount", azureVolumeCount);
        templateModel.put("azureVolumeSize", azureVolumeSize);
        // WHEN
        Response resourceCreationResponse = RestUtil.createEntityRequest(itContext.getContextParam(CloudbreakITContextConstants.CLOUDBREAK_SERVER),
                itContext.getContextParam(IntegrationTestContext.AUTH_TOKEN), FreeMarkerUtil.renderTemplate(azureTemplateCreationTemplate, templateModel))
                .post("/user/templates");
        // THEN
        checkResponse(resourceCreationResponse, HttpStatus.CREATED, ContentType.JSON);
        templateAdditionHelper.handleTemplateAdditions(itContext, resourceCreationResponse.jsonPath().getString("id"), additions);
    }
}
