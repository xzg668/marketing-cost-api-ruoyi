package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用的真实拒绝回执验证：同时使用不存在的工号和流程编号，不提交任何真实业务单据。 */
@Tag("oa-live")
@EnabledIfEnvironmentVariable(named = "OA_WORKFLOW_LIVE_TEST", matches = "true")
class OaWorkflowLiveTest {
  @Test void realOaRejectsNonexistentOperatorAndFlow() throws Exception {
    OaAuthProperties auth = new OaAuthProperties();
    auth.setBaseUrl(required("OA_AUTH_BASE_URL"));
    auth.setCallSysCode(required("OA_AUTH_CALL_SYS_CODE"));
    auth.setCorpId(required("OA_AUTH_CORP_ID"));
    auth.setAppKey(required("OA_AUTH_APP_KEY"));
    auth.setAppSecret(required("OA_AUTH_APP_SECRET"));
    auth.requireConfigured();
    var json = new ObjectMapper();
    var tokens = new OaAccessTokenProvider(auth, json);
    String token = tokens.getAccessToken();
    assertThat(tokens.getAccessToken()).isEqualTo(token);
    System.out.println("OA_WORKFLOW_LIVE_AUTH=acquired-and-reused");
    var settings = new OaWorkflowProperties();
    settings.setBaseUrl(System.getenv("OA_WORKFLOW_BASE_URL"));
    try (var validation = Validation.buildDefaultValidatorFactory()) {
      String suffix = UUID.randomUUID().toString().replace("-", "");
      String invalidEmployee = "NO_SUCH_EMPLOYEE_" + suffix;
      var command = new OaTechnicalDispatchRequest("NO_SUCH_FLOW_" + suffix, "FI-SC-005", invalidEmployee,
          List.of(new OaTechnicalDispatchRequest.Product("DEBUG-ROW", "DEBUG-PRODUCT", List.of(
              new OaTechnicalDispatchRequest.Assignment(invalidEmployee, "接口拒绝回执测试", List.of("SALARY"))))));
      var body = new OaTechnicalDispatchRequestBuilder(json, validation.getValidator()).build(command, invalidEmployee);
      var result = new OaWorkflowClient(settings, auth, tokens, json).submit(body);
      System.out.println("OA_WORKFLOW_LIVE_RESULT=" + json.writeValueAsString(result));
      assertThat(result.status()).as("这里只验证真实拒绝回执，不能据此宣称真实成功办理已通过")
          .isEqualTo(OaWorkflowResult.Status.REJECTED);
      assertThat(result.httpStatus()).isEqualTo(200);
      assertThat(result.errorCode()).isEqualTo("200031");
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("真实联调缺少环境变量：" + name);
    return value;
  }
}
