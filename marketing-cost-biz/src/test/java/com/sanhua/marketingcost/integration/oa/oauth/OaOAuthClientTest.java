package com.sanhua.marketingcost.integration.oa.oauth;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class OaOAuthClientTest {
  HttpServer server;
  OaOAuthClient client;
  OaOAuthProperties properties;
  List<Map<String,String>> requests = new ArrayList<>();
  String tokenBody = "{\"code\":\"0\",\"status\":200,\"access_token\":\"private-token\",\"expire\":7200}";
  String profile = "{\"code\":\"0\",\"status\":200,\"id\":\"001234\",\"attributes\":{\"job_num\":\"wrong\",\"username\":\"测试技术员\"}}";
  int status = 200;

  @BeforeEach void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/", exchange -> {
      Map<String,String> query = new HashMap<>();
      for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
        String[] value = pair.split("=",2);
        query.put(value[0],URLDecoder.decode(value[1],StandardCharsets.UTF_8));
      }
      query.put("method",exchange.getRequestMethod()); requests.add(query);
      byte[] reply = (exchange.getRequestURI().getPath().endsWith("profile") ? profile : tokenBody).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status,reply.length); exchange.getResponseBody().write(reply); exchange.close();
    });
    server.start(); properties = new OaOAuthProperties();
    properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
    properties.setClientId("app"); properties.setClientSecret("secret+&=");
    properties.setRedirectUri("http://quote.test/api/v1/auth/oa/callback");
    client = new OaOAuthClient(properties,new ObjectMapper());
  }
  @AfterEach void close() { server.stop(0); }

  @Test void usesDocumentedPostQueryAndIdEmployeeNumber() {
    var result = client.authenticate("one-use+code");
    assertThat(result.employeeNo()).isEqualTo("001234");
    assertThat(result.name()).isEqualTo("测试技术员");
    assertThat(result.expiresIn()).isEqualTo(7200);
    assertThat(requests).hasSize(2);
    assertThat(requests.getFirst()).containsEntry("method","POST").containsEntry("code","one-use+code")
        .containsEntry("client_secret","secret+&=").containsEntry("redirect_uri",properties.getRedirectUri());
    assertThat(requests.getLast()).containsEntry("access_token","private-token");
    assertThat(client.authorizeUrl("state")).contains("response_type=code", "client_id=app", "state=state");
  }
  @Test void rejectsBusinessErrorWithoutRetryOrLeakingMessage() {
    tokenBody = "{\"code\":\"1010\",\"status\":400,\"msg\":\"private-token secret+&=\"}";
    assertThatThrownBy(() -> client.authenticate("private-code")).isInstanceOf(OaOAuthException.class)
        .hasMessageContaining("1010").hasMessageNotContaining("private-token").hasMessageNotContaining("secret");
    assertThat(requests).hasSize(1);
  }
  @Test void rejectsMissingIdEvenWhenAttributesContainJobNumber() {
    profile = "{\"code\":\"0\",\"status\":200,\"attributes\":{\"job_num\":\"1234\"}}";
    assertThatThrownBy(() -> client.authenticate("code")).hasMessageContaining("工号 id");
  }
  @Test void rejectsNumericIdToAvoidLossOfLeadingZeros() {
    profile = "{\"code\":\"0\",\"status\":200,\"id\":1234}";
    assertThatThrownBy(() -> client.authenticate("code")).hasMessageContaining("工号 id");
  }
  @Test void rejectsNonJsonAndHttpFailures() {
    tokenBody = "not-json";
    assertThatThrownBy(() -> client.authenticate("code")).isInstanceOf(OaOAuthException.class);
    status=302;
    assertThatThrownBy(() -> client.authenticate("another-code")).hasMessageContaining("请求失败");
  }
  @Test void rejectsMissingTokenAndExpiry() {
    tokenBody = "{\"code\":\"0\",\"status\":200}";
    assertThatThrownBy(() -> client.authenticate("code")).hasMessageContaining("返回内容不完整");
  }
}
