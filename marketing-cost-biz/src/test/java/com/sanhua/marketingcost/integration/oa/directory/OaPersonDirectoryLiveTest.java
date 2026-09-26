package com.sanhua.marketingcost.integration.oa.directory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用的 OA 只读联调：调用生产代码获取完整人员快照，不启动应用或写入数据库。 */
@Tag("oa-live")
@EnabledIfEnvironmentVariable(named = "OA_LIVE_TEST", matches = "true")
class OaPersonDirectoryLiveTest {
  @Test
  void loadsRealDirectoryThroughSharedTokenProvider() throws Exception {
    OaAuthProperties auth = new OaAuthProperties();
    auth.setBaseUrl(required("OA_AUTH_BASE_URL"));
    auth.setCallSysCode(required("OA_AUTH_CALL_SYS_CODE"));
    auth.setCorpId(required("OA_AUTH_CORP_ID"));
    auth.setAppKey(required("OA_AUTH_APP_KEY"));
    auth.setAppSecret(required("OA_AUTH_APP_SECRET"));
    auth.requireConfigured();

    OaPersonDirectoryProperties directory = new OaPersonDirectoryProperties();
    directory.setEnabled(true);
    directory.setQueryBaseUrl(required("OA_PERSON_DIRECTORY_QUERY_BASE_URL"));
    directory.setDetailBaseUrl(required("OA_PERSON_DIRECTORY_DETAIL_BASE_URL"));
    directory.validate();

    ObjectMapper json = new ObjectMapper();
    OaAccessTokenProvider tokens = new OaAccessTokenProvider(auth, json);
    System.out.println("OA_LIVE_PHASE=acquire-token");
    String firstToken = tokens.getAccessToken();
    assertTrue(firstToken.equals(tokens.getAccessToken()), "连续调用应复用缓存令牌");
    System.out.println("OA_LIVE_PHASE=token-acquired-and-reused; loading-directory");
    OaDirectorySnapshot snapshot = new HttpOaPersonDirectoryGateway(
        directory, auth, tokens, json).load();
    assertTrue(snapshot.organizationCount() > 0, "OA 未返回组织数据");
    assertTrue(snapshot.employeeCount() > 0, "OA 未返回人员数据");
    assertFalse(snapshot.people().isEmpty(), "目标部门未获取到人员数据");
    System.out.println("OA_LIVE_RESULT=" + json.writeValueAsString(Map.of(
        "organizations", snapshot.organizationCount(),
        "employees", snapshot.employeeCount(),
        "selectedPeopleWithDetails", snapshot.people().size(),
        "missingDepartments", snapshot.missingDepartments())));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("OA 真实联调缺少环境变量：" + name);
    }
    return value;
  }
}
