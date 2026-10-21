package com.sanhua.marketingcost.integration.oa.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class HttpOaPersonDirectoryGatewayTest {
  private HttpServer server;
  private OaPersonDirectoryProperties properties;
  private OaAuthProperties authProperties;
  private OaAccessTokenProvider tokenProvider;
  private final AtomicBoolean failDetail = new AtomicBoolean();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger detailRequests = new AtomicInteger();
  private final List<String> detailQueries = new ArrayList<>();
  private int rejectFirstDetail;
  private boolean alwaysRejectDetail;
  private final Logger logger = (Logger) LoggerFactory.getLogger(HttpOaPersonDirectoryGateway.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void startServer() throws Exception {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    events.start();
    logger.addAppender(events);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/openserver/oauth2/authorize", exchange -> respond(exchange,
        "{\"errcode\":0,\"code\":\"AUTH_CODE\"}"));
    server.createContext("/openserver/oauth2/access_token", exchange -> respond(exchange,
        "{\"errcode\":0,\"accessToken\":\"TOKEN-" + tokenRequests.incrementAndGet()
            + "\",\"expires_in\":7200}"));
    server.createContext("/openserver/api/hrm/restful/queryOrg", exchange -> respond(exchange, """
        {"message":{"errcode":0},"data":{"total":4,"data":[
          {"id":"1","name":"集团","parent":"0"},
          {"id":"2","name":"商用制冷业务单元","parent":"1"},
          {"id":"3","name":"商用四通阀事业部","parent":"2"},
          {"id":"4","name":"制造部","parent":"3"}
        ]}}
        """));
    server.createContext("/openserver/api/hrm/restful/queryEmployee", exchange -> respond(exchange, """
        {"message":{"errcode":0},"data":{"total":2,"data":[
          {"id":"U1","department":["4"],"username":"张三","job_num":"E001"},
          {"id":"U2","department":["1"],"username":"范围外人员","job_num":"E002"}
        ]}}
        """));
    server.createContext("/openserver/user/v3/findUser", exchange -> {
      int request = detailRequests.incrementAndGet();
      detailQueries.add(exchange.getRequestURI().getRawQuery());
      if (alwaysRejectDetail || request == 1 && rejectFirstDetail != 0) {
        if (rejectFirstDetail == 401) {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        } else {
          respond(exchange, rejectFirstDetail == 1 ? "{\"errcode\":200007}"
              : "{\"message\":{\"errcode\":200007}}");
        }
        return;
      }
      if (failDetail.get()) {
        respond(exchange, "{\"message\":{\"errcode\":500},\"data\":[]}");
      } else {
        respond(exchange, """
            {"message":{"errcode":0},"data":[{
              "userid":"U1","jobNum":"E001","status":"normal",
              "positionInfo":{"id":"P1","name":"技术员"}
            }]}
            """);
      }
    });
    server.start();

    properties = new OaPersonDirectoryProperties();
    properties.setEnabled(true);
    properties.setQueryBaseUrl(baseUrl());
    properties.setDetailBaseUrl(baseUrl());
    properties.setMaxAttempts(1);
    authProperties = new OaAuthProperties();
    authProperties.setBaseUrl(baseUrl());
    authProperties.setCallSysCode("TEST_CALLER");
    authProperties.setCorpId("CORP");
    authProperties.setAppKey("KEY");
    authProperties.setAppSecret("SECRET");
    authProperties.setMaxAttempts(1);
    tokenProvider = new OaAccessTokenProvider(authProperties, new ObjectMapper());
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    logger.detachAppender(events);
    logger.setLevel(previousLevel);
    events.stop();
  }

  @Test
  void logsAggregateProgressAtHundredsAndFinalRemainder() throws Exception {
    var json = new ObjectMapper();
    var people = java.util.stream.IntStream.rangeClosed(1, 205).mapToObj(index -> Map.of(
        "id", "U" + index, "department", List.of("4"), "username", "测试人员" + index,
        "job_num", "E" + index)).toList();
    replaceEmployees(json.writeValueAsString(Map.of("message", Map.of("errcode", 0),
        "data", Map.of("total", 205, "data", people))));
    server.removeContext("/openserver/user/v3/findUser");
    server.createContext("/openserver/user/v3/findUser", exchange -> {
      detailRequests.incrementAndGet();
      String number = exchange.getRequestURI().getRawQuery().split("jobNum=E")[1];
      respond(exchange, json.writeValueAsString(Map.of("message", Map.of("errcode", 0),
          "data", List.of(Map.of("userid", "U" + number, "jobNum", "E" + number, "status", "normal")))));
    });
    String batchCallId;
    try (var batch = OaInterfaceLog.start("OA_DIRECTORY_SYNC")) {
      batchCallId = batch.id();
      assertThat(gateway().load().people()).hasSize(205);
      batch.success();
    }
    assertThat(detailRequests.get()).isEqualTo(205);
    List<String> progress = progressMessages();
    assertThat(progress).hasSize(6).allSatisfy(message -> assertThat(message)
        .contains("callId=" + batchCallId, "durationMs=").doesNotContain("测试人员", "TOKEN-", "jobNum"));
    assertThat(progress.get(0)).contains("stage=ORGANIZATIONS completed=4 total=4");
    assertThat(progress.get(1)).contains("stage=EMPLOYEES completed=205 total=205");
    assertThat(progress.subList(2, 6)).satisfiesExactly(
        message -> assertThat(message).contains("stage=DETAILS completed=0 total=205"),
        message -> assertThat(message).contains("stage=DETAILS completed=100 total=205"),
        message -> assertThat(message).contains("stage=DETAILS completed=200 total=205"),
        message -> assertThat(message).contains("stage=DETAILS completed=205 total=205"));
    assertThat(events.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
  }

  @Test
  void noSelectedPeopleReportsZeroOnceWithoutDetailRequests() {
    replaceEmployees("""
        {"message":{"errcode":0},"data":{"total":0,"data":[]}}
        """);
    assertThat(gateway().load().people()).isEmpty();
    assertThat(detailRequests.get()).isZero();
    assertThat(progressMessages()).filteredOn(message -> message.contains("stage=DETAILS"))
        .singleElement().asString().contains("completed=0 total=0");
  }

  private List<String> progressMessages() {
    return events.list.stream().map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.startsWith("OA_DIRECTORY_PROGRESS")).toList();
  }

  @Test
  void loadsCompletePersonAndDepartmentFields() {
    OaDirectorySnapshot snapshot = gateway().load();

    assertThat(snapshot.organizationCount()).isEqualTo(4);
    assertThat(snapshot.employeeCount()).isEqualTo(2);
    assertThat(snapshot.people()).singleElement().satisfies(person -> {
      assertThat(person.employeeNo()).isEqualTo("E001");
      assertThat(person.name()).isEqualTo("张三");
      assertThat(person.positionName()).isEqualTo("技术员");
      assertThat(person.employmentStatus()).isEqualTo("normal");
      assertThat(person.targetDepartmentPaths())
          .containsExactly("商用制冷业务单元/商用四通阀事业部");
      assertThat(person.actualDepartmentPaths())
          .containsExactly("集团/商用制冷业务单元/商用四通阀事业部/制造部");
      assertThat(person.matchTypes()).containsExactly("下级部门");
    });
    assertThat(snapshot.missingDepartments()).contains("商用制冷业务单元/越南事业部");
  }

  @Test
  void mergesRepeatedEmployeeAcrossDepartmentsBeforeLoadingDetails() {
    replaceEmployees("""
        {"message":{"errcode":0},"data":{"total":3,"data":[
          {"id":"U1","department":["3"],"username":"张三","job_num":"E001"},
          {"id":"U1","department":["4"],"username":"张三","job_num":"E001"},
          {"id":"U1","department":["4"],"username":"张三","job_num":"E001"}
        ]}}
        """);
    assertThat(gateway().load().people()).singleElement().satisfies(person -> {
      assertThat(person.employeeNo()).isEqualTo("E001");
      assertThat(person.oaDepartmentIds()).containsExactlyInAnyOrder("3", "4");
      assertThat(person.actualDepartmentPaths()).hasSize(2);
    });
    assertThat(detailRequests.get()).isEqualTo(1);
  }

  @Test
  void rejectsAmbiguousEmployeeNumberInsteadOfChoosingAnArbitraryIdentity() {
    replaceEmployees("""
        {"message":{"errcode":0},"data":{"total":2,"data":[
          {"id":"U1","department":["4"],"username":"张三","job_num":"E001"},
          {"id":"U2","department":["4"],"username":"张三","job_num":"E001"}
        ]}}
        """);
    assertThatThrownBy(() -> gateway().load()).isInstanceOf(OaPersonDirectoryException.class)
        .hasMessageContaining("同一工号匹配到多个 OA 人员 ID");
    assertThat(detailRequests.get()).isZero();
  }

  private void replaceEmployees(String response) {
    server.removeContext("/openserver/api/hrm/restful/queryEmployee");
    server.createContext("/openserver/api/hrm/restful/queryEmployee", exchange -> respond(exchange, response));
  }

  @Test
  void rejectsWholeSnapshotWhenAnyPersonDetailFails() {
    failDetail.set(true);

    assertThatThrownBy(() -> gateway().load())
        .isInstanceOf(OaPersonDirectoryException.class)
        .hasMessageContaining("JK-03");
    assertThat(tokenRequests.get()).isEqualTo(1);
    assertThat(detailRequests.get()).isEqualTo(1);
    assertThat(progressMessages()).anySatisfy(message -> assertThat(message)
        .contains("stage=DETAILS completed=0 total=1"));
    assertThat(progressMessages()).noneSatisfy(message -> assertThat(message)
        .contains("stage=DETAILS completed=1 total=1"));
  }

  @Test
  void reusesTokenAcrossSeparateDirectoryLoads() {
    assertThat(gateway().load().people()).hasSize(1);
    assertThat(gateway().load().people()).hasSize(1);
    assertThat(tokenRequests.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 1, 200007})
  void refreshesRejectedTokenAndRecoversPersonDetails(int failure) {
    rejectFirstDetail = failure;
    assertThat(gateway().load().people()).hasSize(1);
    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(detailRequests.get()).isEqualTo(2);
    assertThat(detailQueries).containsExactly(
        "access_token=TOKEN-1&jobNum=E001", "access_token=TOKEN-2&jobNum=E001");
  }

  @Test
  void refreshesOnLaterPageWithoutLosingEarlierOrganizations() throws Exception {
    properties.setPageSize(2);
    AtomicInteger secondPageCalls = new AtomicInteger();
    List<String> pageTokens = new ArrayList<>();
    server.removeContext("/openserver/api/hrm/restful/queryOrg");
    server.createContext("/openserver/api/hrm/restful/queryOrg", exchange -> {
      pageTokens.add(exchange.getRequestURI().getRawQuery());
      int current = new ObjectMapper().readTree(exchange.getRequestBody()).path("current").asInt();
      if (current == 1) {
        respond(exchange, """
            {"message":{"errcode":0},"data":{"total":4,"data":[
              {"id":"1","name":"集团","parent":"0"},
              {"id":"2","name":"商用制冷业务单元","parent":"1"}
            ]}}
            """);
      } else if (secondPageCalls.incrementAndGet() == 1) {
        respond(exchange, "{\"message\":{\"errcode\":200007}}");
      } else {
        respond(exchange, """
            {"message":{"errcode":0},"data":{"total":4,"data":[
              {"id":"3","name":"商用四通阀事业部","parent":"2"},
              {"id":"4","name":"制造部","parent":"3"}
            ]}}
            """);
      }
    });
    OaDirectorySnapshot snapshot = gateway().load();
    assertThat(snapshot.organizationCount()).isEqualTo(4);
    assertThat(snapshot.people()).hasSize(1);
    assertThat(pageTokens).containsExactly(
        "access_token=TOKEN-1", "access_token=TOKEN-1", "access_token=TOKEN-2");
    assertThat(detailQueries).containsExactly("access_token=TOKEN-2&jobNum=E001");
    assertThat(tokenRequests.get()).isEqualTo(2);
  }

  @Test
  void repeatedTokenRejectionStopsAfterOneAuthenticatedRetry() {
    alwaysRejectDetail = true;
    assertThatThrownBy(() -> gateway().load())
        .isInstanceOf(OaPersonDirectoryException.class).hasMessageContaining("更新后仍被 OA 拒绝");
    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(detailRequests.get()).isEqualTo(2);
  }

  @Test
  void preservesAuthenticationErrorWhenRefreshingDuringPersonDetails() {
    rejectFirstDetail = 401;
    server.removeContext("/openserver/oauth2/access_token");
    server.createContext("/openserver/oauth2/access_token", exchange -> {
      int count = tokenRequests.incrementAndGet();
      respond(exchange, count == 1
          ? "{\"errcode\":0,\"accessToken\":\"TOKEN-1\",\"expires_in\":7200}"
          : "{\"errcode\":200005}");
    });
    assertThatThrownBy(() -> gateway().load()).isInstanceOf(OaPersonDirectoryException.class)
        .hasMessageContaining("JK-02").hasMessageContaining("200005");
    assertThat(tokenRequests.get()).isEqualTo(2);
    assertThat(detailRequests.get()).isEqualTo(1);
  }

  private HttpOaPersonDirectoryGateway gateway() {
    return new HttpOaPersonDirectoryGateway(properties, authProperties, tokenProvider, new ObjectMapper());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var response = exchange.getResponseBody()) {
      response.write(bytes);
    }
  }
}
