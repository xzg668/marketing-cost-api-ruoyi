package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.Set;
import java.util.ArrayList;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "integration.oa.mode=MOCK",
      "integration.oa.environment=I01_TEST",
          "integration.oa.clients.test.source-system=WEAVER",
      "integration.oa.clients.test.environment=I01_TEST",
      "integration.oa.clients.test.mode=MOCK",
      "integration.oa.clients.test.business-units=COMMERCIAL",
      "integration.oa.clients.test.secret=i01-test-only-token-at-least-32-characters"
    })
class OaQuotationIntegrationTest extends BomMapperTestBase {
  static final String URL = "/open-api/v1/oa/quotation-requests";
  static final String TOKEN = "Bearer i01-test-only-token-at-least-32-characters";
  static final OaPeer PEER = new OaPeer("WEAVER", "I01_TEST", Set.of("COMMERCIAL"));
  @Autowired OaQuotationService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @SpyBean OaMessageRepository repository;

  @Test void rejectionNodeIsSavedWithOriginalWorkflowAndCannotBeOverwrittenByReplay() throws Exception {
    var source = request(1).put("RejectToNodeid", "001327000000123456789");
    var received = service.receive(PEER, source.toString());
    long formId = received.at("/data/quoteId").asLong();
    assertThat(jdbc.queryForObject("SELECT reject_to_node_id FROM lp_oa_quote_document WHERE oa_form_id=?",
        String.class, formId)).isEqualTo("001327000000123456789");
    assertThat(service.receive(PEER, source.toString())).isEqualTo(received);
    assertThatThrownBy(() -> service.receive(PEER, source.deepCopy().put("RejectToNodeid", "OTHER").toString()))
        .hasMessageContaining("不能覆盖");
    assertThat(jdbc.queryForObject("SELECT reject_to_node_id FROM lp_oa_quote_document WHERE oa_form_id=?",
        String.class, formId)).isEqualTo("001327000000123456789");
  }

  @AfterEach
  void resetSpy() {
    reset(repository);
  }

  ObjectNode request(int n) throws Exception {
    var r = OaQuotationRequestMapperTest.sample(n);
    String id = UUID.randomUUID().toString();
    return r.put("requestId", "REQ-" + id)
        .put("formNo", "I01-" + id);
  }

  @Test
  void sixFormsPersistAllSourceFieldsAndReturnMappings() throws Exception {
    for (int n = 1; n <= 6; n++) {
      var r = request(n);
      JsonNode result = service.receive(PEER, r.toString());
      var d = result.path("data");
      assertThat(result.path("code").asText()).isEqualTo("0");
      assertThat(d.path("status").asText()).isEqualTo("SUCCEEDED");
      long id = d.path("quoteId").asLong(),
          item = d.path("itemMappings").get(0).path("quoteItemId").asLong();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_oa_form_header_extra_field WHERE oa_form_id=?",
                  Integer.class,
                  id))
          .isEqualTo(r.path("mainData").size());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_oa_form_item_extra_field WHERE oa_form_item_id=? AND"
                      + " source_field_path LIKE 'detailData[0].fields.%'",
                  Integer.class, item))
          .isEqualTo(r.path("detailData").get(0).path("fields").size());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_quote_bom_status WHERE oa_form_item_id=?",
                  Integer.class,
                  item))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT business_unit_type FROM oa_form WHERE id=?", String.class, id))
          .isEqualTo("COMMERCIAL");
      assertThat(d.path("itemMappings").get(0).path("rowId"))
          .isEqualTo(r.path("detailData").get(0).path("rowId"));
      assertThat(service.receive(PEER, r.toString())).isEqualTo(result);
    }
  }

  @Test
  void sc006ReceivesOptionalPreliminaryBoardTenderFieldAndKeepsItsSourceValue()
      throws Exception {
    for (String preliminary : new String[] {null, "是", "否"}) {
      var source = request(2);
      var main = (ObjectNode) source.path("mainData");
      main.remove("sfzbxqjzdbhcp");
      if (preliminary == null) main.remove("ywycbqrsfzbxqjzdbhc");
      else main.put("ywycbqrsfzbxqjzdbhc", preliminary);

      var response =
          http.perform(
                  post(URL)
                      .header("Authorization", TOKEN)
                      .contentType("application/json")
                      .content(source.toString()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.code").value("0"))
              .andReturn();
      var result = json.readTree(response.getResponse().getContentAsByteArray());
      long formId = result.at("/data/quoteId").asLong();
      assertThat(jdbc.queryForObject("SELECT board_tender FROM oa_form WHERE id=?", Object.class, formId))
          .isNull();
      var savedValues =
          jdbc.queryForList(
              "SELECT field_value FROM lp_oa_form_header_extra_field WHERE oa_form_id=? AND field_code=?",
              String.class,
              formId,
              "ywycbqrsfzbxqjzdbhc");
      if (preliminary == null) assertThat(savedValues).isEmpty();
      else assertThat(savedValues).containsExactly(preliminary);
      assertThat(service.receive(PEER, source.toString())).isEqualTo(result);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class, source.path("formNo").asText()))
          .isEqualTo(1);
    }
  }

  @Test
  void numericStringsAreSavedExactlyAndTheOriginalJsonRemainsTextual() throws Exception {
    var source = request(2);
    ((ObjectNode) source.path("mainData")).put("hl", "6.8000").put("hsstjjhs", "120000.00");
    ((ObjectNode) source.path("detailData").get(0)).put("rowIndex", "1");
    ((ObjectNode) source.path("detailData").get(0).path("fields"))
        .put("ysfyz", "0.7534").put("yjnylwz", "0.766");
    var response = http.perform(post(URL).header("Authorization", TOKEN)
            .contentType("application/json").content(source.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
        .andReturn();
    var result = json.readTree(response.getResponse().getContentAsByteArray());
    long formId = result.at("/data/quoteId").asLong();
    long itemId = result.at("/data/itemMappings/0/quoteItemId").asLong();
    assertThat(jdbc.queryForObject("SELECT exchange_rate FROM oa_form WHERE id=?", java.math.BigDecimal.class, formId))
        .isEqualByComparingTo("6.8000");
    assertThat(jdbc.queryForObject("SELECT shipping_fee FROM oa_form_item WHERE id=?", java.math.BigDecimal.class, itemId))
        .isEqualByComparingTo("0.7534");
    var raw = jdbc.queryForObject("SELECT raw_payload FROM lp_oa_integration_message WHERE request_id=?",
        String.class, source.path("requestId").asText());
    assertThat(json.readTree(raw).at("/mainData/hl").isTextual()).isTrue();
    assertThat(json.readTree(raw).at("/mainData/hl").textValue()).isEqualTo("6.8000");
    assertThat(service.receive(PEER, source.toString())).isEqualTo(result);
  }

  @Test
  void missingOaPackagingDoesNotRejectOrChangeTheFinishedProductIdentity() throws Exception {
    var source = request(2);
    var fields = (ObjectNode) source.path("detailData").get(0).path("fields");
    fields.put("lh", "1145100050625");
    fields.remove("bzfs");
    fields.remove("bzfsx");
    var response = http.perform(post(URL).header("Authorization", TOKEN)
            .contentType("application/json").content(source.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
        .andReturn();
    var result = json.readTree(response.getResponse().getContentAsByteArray());
    long itemId = result.at("/data/itemMappings/0/quoteItemId").asLong();
    assertThat(jdbc.queryForObject("SELECT material_no FROM oa_form_item WHERE id=?", String.class, itemId))
        .isEqualTo("1145100050625");
    assertThat(jdbc.queryForObject("SELECT package_method FROM oa_form_item WHERE id=?", Object.class, itemId))
        .isNull();
    assertThat(jdbc.queryForObject("SELECT product_code FROM lp_quote_bom_status WHERE oa_form_item_id=?",
        String.class, itemId)).isEqualTo("1145100050625");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_form_item_extra_field WHERE oa_form_item_id=? "
        + "AND field_code IN ('bzfs','bzfsx')", Integer.class, itemId)).isZero();
    assertThat(service.receive(PEER, source.toString())).isEqualTo(result);
  }

  @Test
  void businessRejectionReturnsHttp200AndDoesNotCreateAQuotation() throws Exception {
    var source = request(2);
    ((ObjectNode) source.path("mainData")).put("hl", "无法解析的汇率");
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      http.perform(post(URL).header("Authorization", TOKEN)
              .contentType("application/json").content(source.toString()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.data.status").value("REJECTED"))
          .andExpect(jsonPath("$.data.errors[0].field").value("mainData.hl"));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class,
          source.path("formNo").asText())).isZero();
      var logged = json.readTree(events.list.getLast().getFormattedMessage());
      assertThat(logged.path("httpStatus").asInt()).isEqualTo(200);
      assertThat(logged.path("businessOutcome").asText()).isEqualTo("REJECTED");
      assertThat(logged.path("businessCode").asText()).isEqualTo("VALIDATION_FAILED");
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void sameDocumentCannotOverwriteSourceOrReplaceProductRows() throws Exception {
    var r = request(2);
    var first = service.receive(PEER, r.toString());
    long form = first.path("data").path("quoteId").asLong();
    long item = first.path("data").path("itemMappings").get(0).path("quoteItemId").asLong();
    String originalCustomer = jdbc.queryForObject("SELECT customer FROM oa_form WHERE id=?", String.class, form);
    var changed = r.deepCopy();
    ((ObjectNode) changed.path("mainData")).put("khmc", "修改后的客户");
    ((ObjectNode) changed.path("detailData").get(0)).put("rowId", "RECREATED-ROW");
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(changed.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    assertThat(jdbc.queryForObject("SELECT customer FROM oa_form WHERE id=?", String.class, form)).isEqualTo(originalCustomer);
    assertThat(jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? AND deleted=0", Long.class, form)).containsExactly(item);
    assertThat(jdbc.queryForObject("SELECT external_document_id FROM lp_oa_quote_document WHERE oa_form_id=?", String.class, form)).isEqualTo(r.path("requestId").asText());
    assertThat(jdbc.queryForObject("SELECT source_version FROM lp_oa_quote_document WHERE oa_form_id=?", Long.class, form)).isEqualTo(1L);
    assertThat(service.receive(PEER, r.toString())).isEqualTo(first);
  }

  @Test
  void sameFormNumberWithAnotherOaIdCannotCreateOrOverwriteQuote() throws Exception {
    var r = request(2);
    var first = service.receive(PEER, r.toString());
    var another = r.deepCopy().put("requestId", "ANOTHER-" + UUID.randomUUID());
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(another.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("QUOTE_NUMBER_OWNED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class, r.path("formNo").asText())).isEqualTo(1);
    assertThat(service.receive(PEER, r.toString())).isEqualTo(first);
  }

  @Test
  void rejectedFirstAttemptCanBeCorrectedAndSubmittedWithSameOaId() throws Exception {
    var r = request(2);
    var invalid = r.deepCopy();
    ((ObjectNode) invalid.path("mainData")).putArray("sqdw").add("invalid");
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(invalid.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
    assertThat(service.receive(PEER, r.toString()).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void incompleteSourceIsSavedButBlockedAndNoFakeBomIdentity() throws Exception {
    var r = request(1);
    ((ObjectNode) r.path("mainData")).putNull("cpsyb").putNull("sqsj");
    ((ObjectNode) r.path("detailData").get(0).path("fields")).putNull("lh").putNull("shxh");
    var result = service.receive(PEER, r.toString());
    long id = result.path("data").path("quoteId").asLong();
    assertThat(result.path("data").path("inputStatus").asText()).isEqualTo("BLOCKED");
    assertThat(jdbc.queryForObject("SELECT apply_date FROM oa_form WHERE id=?", Object.class, id))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_status WHERE oa_form_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void concurrentSameRequestCreatesOnlyOneQuote() throws Exception {
    String raw = request(2).toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var results =
          java.util.stream.IntStream.range(0, 4)
              .mapToObj(i -> pool.submit(() -> service.receive(PEER, raw)))
              .toList();
      JsonNode first = results.getFirst().get(30, TimeUnit.SECONDS);
      for (var result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(first);
    }
  }

  @Test
  void failedReceiptRollsBackAllBusinessWrites() throws Exception {
    var r = request(2);
    doThrow(new DataIntegrityViolationException("injected"))
        .when(repository)
        .completeQuotation(anyLong(), anyString());
    assertThatThrownBy(() -> service.receive(PEER, r.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(repository, times(1)).completeQuotation(anyLong(), anyString());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM oa_form WHERE oa_no=?",
                Integer.class,
                r.path("formNo").asText()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_oa_quote_document WHERE external_document_id=?",
                Integer.class,
                r.path("requestId").asText()))
        .isZero();
  }

  @Test
  void concurrentDifferentQuotationsAndReplaysKeepEveryDocumentComplete() throws Exception {
    var requests = new ArrayList<ObjectNode>();
    for (int i = 0; i < 8; i++) requests.add(request(i % 6 + 1));
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = requests.stream().map(r -> pool.submit(() -> {
        start.await();
        return service.receive(PEER, r.toString());
      })).toList();
      start.countDown();
      for (int i = 0; i < requests.size(); i++) {
        var result = futures.get(i).get(30, TimeUnit.SECONDS);
        assertCompleteSingleReceipt(requests.get(i), result);
        assertThat(service.receive(PEER, requests.get(i).toString())).isEqualTo(result);
      }
    }
  }

  @Test
  void failedDatabaseTransactionStillLeavesRequestAndResponseInDedicatedFileLog() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      assertThat(logger.isAdditive()).isFalse();
      assertThat(logger.getAppender("EXTERNAL_INTERFACE"))
          .isInstanceOf(ch.qos.logback.core.rolling.RollingFileAppender.class);
      var r = request(2);
      doThrow(new DataIntegrityViolationException("test storage failure"))
          .when(repository).completeQuotation(anyLong(), anyString());
      var response = http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json")
              .content(r.toString()))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
          .andExpect(header().exists("X-Interface-Call-Id")).andReturn();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class,
          r.path("formNo").asText())).isZero();
      var logged = json.readTree(events.list.getLast().getFormattedMessage());
      assertThat(logged.at("/request/json/requestId").asText()).isEqualTo(r.path("requestId").asText());
      assertThat(logged.at("/response/json/code").asText()).isEqualTo("STORAGE_UNAVAILABLE");
      assertThat(logged.path("callId").asText()).isEqualTo(response.getResponse().getHeader("X-Interface-Call-Id"));
      assertThat(logged.toString()).doesNotContain(TOKEN.substring(7));
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void concurrentDifferentDocumentsCannotClaimTheSameQuotationNumber() throws Exception {
    var first = request(1);
    var second = request(1).put("formNo", first.path("formNo").asText());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = java.util.List.of(first, second).stream().map(r -> pool.submit(() -> {
        start.await();
        return http.perform(post(URL).header("Authorization", TOKEN)
            .contentType("application/json").content(r.toString())).andReturn().getResponse();
      })).toList();
      start.countDown();
      var a = futures.get(0).get(30, TimeUnit.SECONDS);
      var b = futures.get(1).get(30, TimeUnit.SECONDS);
      assertThat(java.util.List.of(a.getStatus(), b.getStatus())).containsExactly(200, 200);
      var results = java.util.List.of(json.readTree(a.getContentAsByteArray()),
          json.readTree(b.getContentAsByteArray()));
      assertThat(results).extracting(result -> result.path("code").asText())
          .containsExactlyInAnyOrder("0", "QUOTE_NUMBER_OWNED");
      for (var result : results) {
        assertThat(result.at("/data/status").asText())
            .isEqualTo("0".equals(result.path("code").asText()) ? "SUCCEEDED" : "REJECTED");
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class,
          first.path("formNo").asText())).isEqualTo(1);
    }
  }

  @Test
  void deadlockAfterBusinessWritesAutomaticallyRetriesTheEntireTransaction() throws Exception {
    var r = request(2);
    doThrow(lockFailure(1213)).doCallRealMethod().when(repository).completeQuotation(anyLong(), anyString());
    var response = http.perform(post(URL).header("Authorization", TOKEN)
        .contentType("application/json").content(r.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
        .andReturn().getResponse().getContentAsString();
    assertCompleteSingleReceipt(r, json.readTree(response));
    verify(repository, times(2)).completeQuotation(anyLong(), anyString());
  }

  @Test
  void actualMysqlDeadlockRecoversWithoutCallerResendingEitherQuotation() throws Exception {
    long anchorA = service.receive(PEER, request(1).toString()).at("/data/quoteId").asLong();
    long anchorB = service.receive(PEER, request(1).toString()).at("/data/quoteId").asLong();
    var first = request(2);
    var second = request(3);
    var attempts = new ConcurrentHashMap<String, AtomicInteger>();
    var bothLocked = new CountDownLatch(2);
    doAnswer(invocation -> {
      long messageId = invocation.getArgument(0);
      String requestId = jdbc.queryForObject("SELECT request_id FROM lp_oa_integration_message WHERE id=?",
          String.class, messageId);
      int attempt = attempts.computeIfAbsent(requestId, ignored -> new AtomicInteger()).incrementAndGet();
      if (attempt == 1) {
        boolean isFirst = requestId.equals(first.path("requestId").asText());
        jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, isFirst ? anchorA : anchorB);
        bothLocked.countDown();
        assertThat(bothLocked.await(10, TimeUnit.SECONDS)).isTrue();
        // 两个真实 MySQL 事务以相反顺序锁定既有行，确定触发一次 1213。
        jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, isFirst ? anchorB : anchorA);
      }
      return invocation.callRealMethod();
    }).when(repository).completeQuotation(anyLong(), anyString());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.receive(PEER, first.toString()));
      var b = pool.submit(() -> service.receive(PEER, second.toString()));
      assertCompleteSingleReceipt(first, a.get(30, TimeUnit.SECONDS));
      assertCompleteSingleReceipt(second, b.get(30, TimeUnit.SECONDS));
    }
    assertThat(attempts.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(3);
    verify(repository, times(5)).completeQuotation(anyLong(), anyString()); // 两张预置单 + 两张新单的三次尝试
  }

  @Test
  void lockWaitTimeoutAlsoRetriesAfterRollback() throws Exception {
    var r = request(2);
    doThrow(lockFailure(1205)).doCallRealMethod().when(repository).completeQuotation(anyLong(), anyString());
    assertCompleteSingleReceipt(r, service.receive(PEER, r.toString()));
    verify(repository, times(2)).completeQuotation(anyLong(), anyString());
  }

  @Test
  void persistentDeadlockStopsAfterThreeAttemptsAndLeavesNoPartialDocument() throws Exception {
    var r = request(2);
    doThrow(lockFailure(1213)).when(repository).completeQuotation(anyLong(), anyString());
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(r.toString()))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    verify(repository, times(3)).completeQuotation(anyLong(), anyString());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class,
        r.path("formNo").asText())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_quote_document WHERE external_document_id=?",
        Integer.class, r.path("requestId").asText())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_integration_message WHERE request_id=?",
        Integer.class, r.path("requestId").asText())).isZero();
  }

  private CannotAcquireLockException lockFailure(int code) {
    return new CannotAcquireLockException("injected rollback", new SQLException("injected", "40001", code));
  }

  private void assertCompleteSingleReceipt(ObjectNode r, JsonNode result) {
    assertThat(result.path("code").asText()).isEqualTo("0");
    long formId = result.at("/data/quoteId").asLong();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class,
        r.path("formNo").asText())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form_item WHERE oa_form_id=?", Integer.class,
        formId)).isEqualTo(r.path("detailData").size());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_status WHERE oa_form_id=?", Integer.class,
        formId)).isEqualTo(r.path("detailData").size());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_quote_document WHERE external_document_id=? AND oa_form_id=?",
        Integer.class, r.path("requestId").asText(), formId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM lp_oa_integration_message m JOIN lp_oa_integration_attempt a ON a.message_id=m.id
        WHERE m.request_id=? AND m.status='PROCESSED'
        """, Integer.class, r.path("requestId").asText())).isEqualTo(1);
  }

  @Test
  void bearerAndJsonErrorsHaveContractShapeAndNeverLeakCredentials() throws Exception {
    var r = request(2);
    http.perform(post(URL).contentType("application/json").content(r.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    http.perform(
            post(URL).header("Authorization", TOKEN).contentType("application/json").content("{"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    ((ObjectNode) r.path("mainData")).remove("syb");
    http.perform(
            post(URL)
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content(r.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.errors[0].field").value("mainData.syb"));
    http.perform(
            post(URL)
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content(request(2).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
  }
}
