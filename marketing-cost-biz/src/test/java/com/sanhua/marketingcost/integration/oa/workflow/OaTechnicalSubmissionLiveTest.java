package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用：选人目录中的丁云龙，先核对 OA 工号，再用不存在的流程号验证 I03 真实拒绝回执。 */
@Tag("oa-live")
@EnabledIfEnvironmentVariable(named = "OA_WORKFLOW_LIVE_TEST", matches = "true")
class OaTechnicalSubmissionLiveTest {
  @Test void submitsOnceThroughTheNewClientWithVerifiedTechnicianAndNonexistentFlow() throws Exception {
    var auth = new OaAuthProperties();
    auth.setBaseUrl(required("OA_AUTH_BASE_URL"));
    auth.setCallSysCode(required("OA_AUTH_CALL_SYS_CODE"));
    auth.setCorpId(required("OA_AUTH_CORP_ID"));
    auth.setAppKey(required("OA_AUTH_APP_KEY"));
    auth.setAppSecret(required("OA_AUTH_APP_SECRET"));
    assertThat(auth.getBaseUrl()).as("此测试仅允许文档中的测试 ESB 地址")
        .isEqualTo("http://115.236.90.118:18506/Sanhua/HZ_KG_OA_TST");
    var json = new ObjectMapper();
    var tokens = new OaAccessTokenProvider(auth, json);
    String token = tokens.getAccessToken();
    assertThat(tokens.getAccessToken()).isEqualTo(token);

    String employeeNo = "12211470";
    var query = json.createObjectNode().put("current", 1).put("pageSize", 10);
    query.putArray("jobNumList").add(employeeNo);
    query.putArray("returnFieldList").add("id").add("username").add("job_num");
    var lookup = HttpRequest.newBuilder(URI.create(auth.getBaseUrl()
            + "/openserver/api/hrm/restful/queryEmployee?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
        .timeout(Duration.ofSeconds(30)).header("callSysCode", auth.getCallSysCode())
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(query.toString(), StandardCharsets.UTF_8)).build();
    HttpResponse<String> reply;
    try {
      reply = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
          .send(lookup, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("真实 OA 人员工号核验未完成，尚未调用 I03");
    }
    assertThat(reply.statusCode()).isEqualTo(200);
    var directory = json.readTree(reply.body());
    assertThat(directory.path("message").path("errcode").asText()).isEqualTo("0");
    var people = directory.path("data").path("data");
    assertThat(people.isArray()).isTrue();
    assertThat(people.size()).isEqualTo(1);
    assertThat(people.get(0).path("job_num").asText()).isEqualTo(employeeNo);
    assertThat(people.get(0).path("username").asText()).isEqualTo("丁云龙");
    System.out.println("OA_I03_REAL_PERSON=" + json.writeValueAsString(Map.of(
        "employeeNo", employeeNo, "name", "丁云龙", "source", "OA queryEmployee", "tokenCacheReused", true)));

    String suffix = UUID.randomUUID().toString().replace("-", "");
    var input = new OaTechnicalSubmissionClient.Request("I03_NO_SUCH_FLOW_" + suffix, employeeNo,
        "接口联调样例：" + OaTechnicalSubmissionFixtures.remark(json),
        "https://quote.example.invalid/technical-data/submissions/I03-" + suffix);
    var client = new OaTechnicalSubmissionClient(json,
        new OaWorkflowClient(new OaWorkflowProperties(), auth, tokens, json));
    System.out.println("OA_I03_REAL_REQUEST=" + client.preview(input));
    var result = client.submit(input);
    System.out.println("OA_I03_REAL_RESULT=" + json.writeValueAsString(result));
    assertThat(result.status()).as("只验证真实错误回执，不代表真实流程成功办理")
        .isEqualTo(OaWorkflowResult.Status.REJECTED);
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.errorCode()).isNotBlank();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("真实联调缺少环境变量：" + name);
    return value;
  }
}
