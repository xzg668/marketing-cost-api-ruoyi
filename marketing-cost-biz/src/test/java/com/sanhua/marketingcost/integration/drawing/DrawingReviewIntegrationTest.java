package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.electronicdrawing.*;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrawingReviewIntegrationTest extends BomMapperTestBase {
  @Autowired MockMvc mvc;
  @Autowired DrawingVerificationService verifications;
  @Autowired DrawingVerificationTaskMapper tasks;
  @Autowired DrawingVerificationResultMapper results;
  @Autowired DrawingVerificationCodec codec;
  @Autowired OaFormItemMapper items;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JwtUtils jwt;
  @Autowired QuoteTechnicalDataPersistenceService technical;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @MockBean ElectronicDrawingWorkflowContextPort contexts;
  @MockBean CurrentU9BomGateway u9;
  @MockBean DrawingVerificationClient client;
  private final ObjectMapper json = new ObjectMapper();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalInterfaceLog.class);
  private ListAppender<ILoggingEvent> logs;
  private DrawingVerificationTask task;
  private Long itemId, formId;
  private String oaNo, material, model;
  private final String month = CostPricingPeriodUtils.currentPricingMonth();

  @BeforeAll void migrate() throws Exception {
    // Testcontainers 的默认配置把报文限为 1 MiB；只调整此一次性库，验证超日志上限的完整证据。
    jdbc.execute("SET GLOBAL max_allowed_packet=67108864");
    dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("/db/V338__drawing_verification_evidence.sql"), "/tmp/EDP05-V338.sql");
    var applied = MYSQL.execInContainer("sh", "-c", "mysql --default-character-set=utf8mb4 -uroot -p"
        + MYSQL.getPassword() + " " + MYSQL.getDatabaseName() + " < /tmp/EDP05-V338.sql");
    assertThat(applied.getExitCode()).withFailMessage(applied.getStderr()).isZero();
  }

  @BeforeEach void fixture() {
    logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
    material = "EDP05-" + UUID.randomUUID(); model = "MODEL-" + material; oaNo = material;
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,?,'COMMERCIAL')", oaNo, month);
    formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,sunl_model,product_name,customer_drawing,business_unit_type) VALUES(?,?,?,'热力膨胀阀','DRAW-01','COMMERCIAL')", formId, material, model);
    itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,organization_code,material_name,material_model,drawing_no,active_flag,source_type,import_batch_id) VALUES(?,'COMMERCIAL','热力膨胀阀',?,'DRAW-01',1,'U9_API','EDP-05')", material, model);
    when(contexts.load(any(), any(), any(), any())).thenAnswer(call -> scope());
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    doAnswer(call -> {
      var initial = DrawingVerificationFixtures.initial("EDP05-" + UUID.randomUUID());
      initial.put("differenceCount", 2); initial.putObject("data");
      return codec.parse(initial.toString());
    }).when(client).verify(any());
    task = verifications.start(scope(), null).orElseThrow();
  }

  @AfterEach void evidence(TestInfo info) throws Exception {
    logger.detachAppender(logs); logs.stop();
    String directory = System.getProperty("edp.evidence.dir");
    if (directory != null) {
      Path target = Path.of(directory, "callback-logs"); Files.createDirectories(target);
      Files.write(target.resolve(info.getTestMethod().orElseThrow().getName() + "-" + task.getId() + ".jsonl"),
          logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
  }

  @Test void confirmedDifferencesAndPendingRetryCommitFinalDataAndInitialPlmBeforeReceipt() throws Exception {
    var initial = results.findById(task.getCurrentResultId());
    String originalPlm = initial.getPlmDataJson();
    var confirmed = confirmed();
    var response = send(request(confirmed), 200, "RECEIVED");
    Long resultId = json.readTree(response.getResponse().getContentAsString()).get("resultId").longValue();
    try (var connection = dataSource.getConnection(); var query = connection.prepareStatement(
        "SELECT event_type,plm_source_result_id FROM lp_drawing_verification_result WHERE id=?")) {
      query.setLong(1, resultId);
      try (var rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("MANUAL_CONFIRMATION");
        assertThat(rows.getLong(2)).isEqualTo(initial.getId());
      }
    }
    var saved = results.findById(resultId);
    assertThat(saved.getDataJson()).isEqualTo(confirmed.get("data").toString());
    assertThat(saved.getPlmDataJson()).isEqualTo("[]");
    assertThat(results.findById(initial.getId()).getPlmDataJson()).isEqualTo(originalPlm).contains("V002", "V001", "secret-fixture");
    var current = tasks.selectById(task.getId());
    assertThat(current.getTaskState()).isEqualTo("RECEIVED");
    assertThat(current.getConsumptionState()).isEqualTo("PENDING");
    assertThat(current.getDifferenceCount()).isEqualTo(2);
    verify(client, times(1)).verify(any());
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "wrong-review", "wrong-idempotency", "duplicate", "whitespace"})
  void taskHeadersMustEachExactlyMatchBody(String mode) throws Exception {
    var body = confirmed();
    var request = post(DrawingReviewController.PATH).contentType(MediaType.APPLICATION_JSON).content(body.toString());
    if (!mode.equals("missing")) request.header("X-Review-Task", mode.equals("wrong-review") ? "other" : task.getExternalTaskId());
    request.header("Idempotency-Key", mode.equals("wrong-idempotency") ? "other"
        : mode.equals("whitespace") ? " " + task.getExternalTaskId() : task.getExternalTaskId());
    if (mode.equals("duplicate")) request.header("X-Review-Task", task.getExternalTaskId());
    send(request, 400, "INVALID_REQUEST");
    assertThat(results.findHistory(task.getId())).hasSize(1);
  }

  @Test void unknownTaskIs404EvenWithMatchingHeaders() throws Exception {
    send(request(DrawingVerificationFixtures.confirmed("EDP05-unknown")), 404, "UNKNOWN_TASK");
  }

  @ParameterizedTest @ValueSource(strings = {"status", "confirmedAt", "plm_data", "invalid-json", "missing-field", "missing-body"})
  void invalidConfirmationsNeverReturnSuccess(String mode) throws Exception {
    var body = confirmed();
    switch (mode) {
      case "status" -> body.put("status", "reviewRequired");
      case "confirmedAt" -> body.putNull("confirmedAt");
      case "plm_data" -> body.putArray("plm_data").addObject().put("fileUrl", "https://example.test?sig=must-hide");
      case "missing-field" -> body.remove("differenceCount");
    }
    var request = request(body);
    if (mode.equals("invalid-json")) request.content("{\"token\":\"never-log-invalid-secret\",");
    if (mode.equals("missing-body")) request.content("");
    send(request, 400, List.of("status", "confirmedAt", "plm_data").contains(mode) ? "INVALID_CONFIRMATION" : "INVALID_REQUEST");
    assertThat(results.findHistory(task.getId())).hasSize(1);
    assertThat(logs.list.toString()).doesNotContain("never-log-invalid-secret", "must-hide");
  }

  @ParameterizedTest @ValueSource(strings = {"drawing", "month", "organization", "quote-owner", "revision"})
  void wrongDrawingVersionOrBusinessBindingIs409(String mode) throws Exception {
    var body = confirmed();
    switch (mode) {
      case "drawing" -> ((ObjectNode) body.get("data")).remove("DRAW-01");
      case "month" -> jdbc.update("UPDATE lp_drawing_verification_task SET accounting_month='2025-01' WHERE id=?", task.getId());
      case "organization" -> jdbc.update("UPDATE lp_drawing_verification_task SET material_org_code='OTHER' WHERE id=?", task.getId());
      case "quote-owner" -> jdbc.update("UPDATE oa_form_item SET business_unit_type='HOUSEHOLD' WHERE id=?", itemId);
      case "revision" -> body.put("revision", 0);
    }
    send(request(body), 409, "BINDING_CONFLICT");
    assertThat(results.findHistory(task.getId())).hasSize(1);
  }

  @Test void onlyDeliveryAttemptsAndObjectKeyOrderAreIgnored() throws Exception {
    var body = confirmed(); send(request(body), 200, "RECEIVED");
    var saved = results.findEvent(task.getId(), "MANUAL_CONFIRMATION");
    var before = jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId());
    body.put("deliveryAttempts", 9);
    var reordered = json.createObjectNode();
    var keys = new java.util.ArrayList<String>(); body.fieldNames().forEachRemaining(keys::add);
    java.util.Collections.reverse(keys); keys.forEach(key -> reordered.set(key, body.get(key)));
    send(request(reordered), 200, "DUPLICATE");
    assertThat(results.findHistory(task.getId())).hasSize(2);
    assertThat(results.findById(saved.getId()).getRawSummaryJson()).isEqualTo(saved.getRawSummaryJson());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId())).isEqualTo(before);
  }

  @ParameterizedTest @ValueSource(strings = {"qty", "status", "revision", "deliveryStatus", "confirmedAt", "reviewReasons", "extension"})
  void otherChangesConflictWithoutOverwritingConfirmedEvidence(String field) throws Exception {
    var body = confirmed(); send(request(body), 200, "RECEIVED");
    var saved = results.findEvent(task.getId(), "MANUAL_CONFIRMATION");
    switch (field) {
      case "qty" -> ((ObjectNode) body.path("data").path("DRAW-01").path("params").path("items").get(0)).put("qty", "8");
      case "status" -> body.put("status", "reviewRequired");
      case "revision" -> body.put("revision", 4);
      case "deliveryStatus" -> body.put("deliveryStatus", "delivered");
      case "confirmedAt" -> body.put("confirmedAt", 1792550100001L);
      case "reviewReasons" -> body.putArray("reviewReasons");
      case "extension" -> body.put("newField", "changed");
    }
    send(request(body), 409, "CONTENT_CONFLICT");
    assertThat(results.findHistory(task.getId())).hasSize(2);
    assertThat(results.findById(saved.getId()).getRawSummaryJson()).isEqualTo(saved.getRawSummaryJson());
  }

  @Test void simultaneousCallbacksCommitOnce() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> { start.await(); return mvc.perform(request(confirmed())).andReturn(); });
      var second = executor.submit(() -> { start.await(); return mvc.perform(request(confirmed())).andReturn(); });
      start.countDown();
      var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      for (var response : responses) assertThat(response.getResponse().getStatus()).isEqualTo(200);
      assertThat(responses.stream().map(value -> value.getResponse().getContentAsByteArray())
          .map(value -> { try { return json.readTree(value).get("duplicate").booleanValue(); } catch (Exception e) { throw new RuntimeException(e); } })).containsExactlyInAnyOrder(false, true);
    }
    assertThat(results.findHistory(task.getId())).hasSize(2);
  }

  @Test void lateOldTaskConfirmationPreservesNewActiveTaskAndFrozenVersions() throws Exception {
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,business_unit_type) VALUES(?,?,?,?,?,?,'SUCCESS','COMMERCIAL')",
        material, oaNo, itemId, material, month, month);
    var preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId); preparation.setOaFormItemId(itemId); preparation.setOaNo(oaNo);
    preparation.setQuoteProductCode(material); preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210"); preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setCostPeriodMonth(month); preparation.setPreparationStatus("NEED_TECH");
    preparation.setActiveFlag(1); preparation.setElectronicWorkflowVersion(0); preparations.insert(preparation);
    jdbc.update("INSERT INTO lp_quote_bom_supplement_version(preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,supplement_scope,version_no,version_status,drawing_result_id) VALUES(?,?,?,?,'NON_BARE','NON_BARE_FULL_BOM',1,'SUBMITTED',?)",
        preparation.getId(), oaNo, itemId, material, task.getCurrentResultId());
    var techTask = new QuoteTechTask(); techTask.setTaskNo(oaNo); techTask.setOaFormId(formId); techTask.setOaFormItemId(itemId);
    techTask.setOaNo(oaNo); techTask.setAccountingMonth(month); techTask.setBusinessUnitType("COMMERCIAL"); techTask.setApplicableOrgCode("210");
    techTask.setAssigneeUserId(101L); techTask.setAssigneeName("测试技术员");
    Long techTaskId = technical.createTask(techTask).getId();
    var product = new QuoteTechProduct(); product.setTaskId(techTaskId); product.setOaFormItemId(itemId);
    product.setQuoteNo(oaNo); product.setAccountingMonth(month); product.setContentSchemaVersion(2);
    product.setMaterialNo(material); product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{}");
    Long productId = technical.createProduct(product).getId();
    String snapshot = "{\"resultId\":" + task.getCurrentResultId() + "}";
    jdbc.update("INSERT INTO lp_quote_tech_data_version(product_id,version_no,version_status,content_schema_version,drawing_bom_json,reference_snapshot_json,source_facts_json,content_fingerprint) VALUES(?,1,'FROZEN',2,?,?,?,?)",
        productId, snapshot, snapshot, snapshot, DrawingVerificationCodec.sha256(snapshot));
    var frozenCost = jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?", material);
    var frozenSource = jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE oa_form_item_id=?", itemId);
    var frozenTechnical = jdbc.queryForMap("SELECT * FROM lp_quote_tech_data_version WHERE product_id=?", productId);
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", model + "-NEW", itemId);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=? WHERE material_code=?", model + "-NEW", material);
    var newer = verifications.start(scope(), null).orElseThrow();
    var newBefore = jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", newer.getId());
    send(request(confirmed()), 200, "RECEIVED");
    var old = tasks.selectById(task.getId());
    assertThat(old.getTaskState()).isEqualTo("SUPERSEDED"); assertThat(old.getConsumptionState()).isEqualTo("NOT_READY");
    assertThat(results.findHistory(old.getId())).hasSize(2);
    assertThat(results.findById(old.getCurrentResultId()).getPlmSourceResultId()).isEqualTo(task.getCurrentResultId());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", newer.getId())).isEqualTo(newBefore);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?", material)).isEqualTo(frozenCost);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE oa_form_item_id=?", itemId)).isEqualTo(frozenSource);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_tech_data_version WHERE product_id=?", productId)).isEqualTo(frozenTechnical);
  }

  @Test void laterManualConfirmationAddsVersionAfterInitialZeroDifferenceAdoption() throws Exception {
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", model + "-AUTO", itemId);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=? WHERE material_code=?", model + "-AUTO", material);
    doReturn(codec.parse(DrawingVerificationFixtures.initial("EDP05-auto-" + UUID.randomUUID()).toString())).when(client).verify(any());
    task = verifications.start(scope(), null).orElseThrow();
    Long originalResult = task.getCurrentResultId();
    assertThat(task.getTaskState()).isEqualTo("RECEIVED");
    send(request(confirmed()), 200, "RECEIVED");
    assertThat(results.findHistory(task.getId())).hasSize(2);
    assertThat(tasks.selectById(task.getId()).getCurrentResultId()).isNotEqualTo(originalResult);
    assertThat(results.findById(originalResult).getEventType()).isEqualTo("INITIAL");
  }

  @Test void databaseFailureRollsBackAndRetryCanCommit() throws Exception {
    var before = jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId());
    jdbc.execute("CREATE TRIGGER edp05_fail_insert BEFORE INSERT ON lp_drawing_verification_result FOR EACH ROW BEGIN IF NEW.event_type='MANUAL_CONFIRMATION' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='edp05 controlled persistence failure'; END IF; END");
    try { send(request(confirmed()), 503, "STORAGE_FAILED"); }
    finally { jdbc.execute("DROP TRIGGER edp05_fail_insert"); }
    assertThat(results.findHistory(task.getId())).hasSize(1);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId())).isEqualTo(before);
    send(request(confirmed()), 200, "RECEIVED");
  }

  @Test void failedContinuationCannotUndoReceiptAndDuplicateDoesNotResetProgress() throws Exception {
    send(request(confirmed()), 200, "RECEIVED");
    Long resultId = tasks.selectById(task.getId()).getCurrentResultId();
    // 消费是独立事务；这里真实触发数据库失败，验证接收事务已经独立提交。
    assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
        jdbc.update("UPDATE lp_drawing_verification_task SET consumed_result_id=-1,consumption_state='COMPLETE' WHERE id=?", task.getId()))).isInstanceOf(RuntimeException.class);
    assertThat(tasks.selectById(task.getId()).getConsumptionState()).isEqualTo("PENDING");
    jdbc.update("UPDATE lp_drawing_verification_task SET consumption_state='FAILED',error_code='CONSUMPTION_FAILED' WHERE id=?", task.getId());
    var failed = jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId());
    send(request(confirmed().put("deliveryAttempts", 2)), 200, "DUPLICATE");
    assertThat(jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId())).isEqualTo(failed);
    assertThat(results.findById(resultId)).isNotNull(); assertThat(results.findHistory(task.getId())).hasSize(2);
    verify(client, times(1)).verify(any());
  }

  @Test void onlyExactPostIsPublicAndNormalJwtAuthenticationStillWorks() throws Exception {
    send(request(confirmed()), 200, "RECEIVED");
    for (var request : List.of(get(DrawingReviewController.PATH), post(DrawingReviewController.PATH + "/extra"),
        post("/integration/v1/drawings/other"), get("/api/v1/auth/me"))) {
      mvc.perform(request).andExpect(status().isUnauthorized());
    }
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt.generateToken("admin", "COMMERCIAL")))
        .andExpect(status().isOk()).andExpect(jsonPath("code").value(0));
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test void incomingSignedUrlsAreRedactedOnceWithoutChangingStoredEvidence() throws Exception {
    var body = confirmed(); body.putObject("drafts").put("fileUrl", "https://files.example.test/a?signature=callback-secret");
    send(request(body).header("Authorization", "Bearer invalid-credential").header("Cookie", "secret=cookie-secret"), 200, "RECEIVED");
    assertThat(logs.list).hasSize(1);
    String logged = logs.list.getFirst().getFormattedMessage();
    assertThat(logged).contains("[REDACTED]", "X-Review-Task", "Idempotency-Key", "X-Request-ID")
        .doesNotContain("callback-secret", "invalid-credential", "cookie-secret");
    assertThat(results.findEvent(task.getId(), "MANUAL_CONFIRMATION").getRawSummaryJson()).contains("callback-secret");
  }

  @Test void oversizedCallbackRetainsCompleteEvidenceAndReportsLogCaptureLimit() throws Exception {
    var body = confirmed(); body.putObject("drafts").put("large", "x".repeat(ExternalInterfaceLog.MAX_BODY_BYTES + 1024));
    send(request(body), 200, "RECEIVED");
    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getFormattedMessage()).contains("AT_CAPTURE_LIMIT").doesNotContain("x".repeat(100));
    assertThat(results.findEvent(task.getId(), "MANUAL_CONFIRMATION").getRawSummaryJson()).isEqualTo(body.toString());
  }

  private ObjectNode confirmed() { return DrawingVerificationFixtures.confirmed(task.getExternalTaskId()); }

  private MockHttpServletRequestBuilder request(ObjectNode body) {
    return post(DrawingReviewController.PATH).contentType(MediaType.APPLICATION_JSON).content(body.toString())
        .header("X-Review-Task", body.get("taskId").textValue())
        .header("Idempotency-Key", body.get("taskId").textValue())
        .header("X-Request-ID", "trace-" + task.getId());
  }

  private MvcResult send(MockHttpServletRequestBuilder request, int expectedStatus, String outcome) throws Exception {
    var response = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String callId = response.getResponse().getHeader("X-Interface-Call-Id");
    assertThat(callId).isNotBlank();
    List<JsonNode> exchanges = logs.list.stream().map(event -> {
      try { return json.readTree(event.getFormattedMessage()); }
      catch (Exception e) { throw new IllegalStateException(e); }
    }).filter(event -> callId.equals(event.path("callId").asText())).toList();
    assertThat(exchanges).hasSize(1);
    assertThat(exchanges.getFirst().path("httpStatus").asInt()).isEqualTo(expectedStatus);
    assertThat(exchanges.getFirst().path("businessOutcome").asText()).isEqualTo(outcome);
    return response;
  }

  private ElectronicDrawingWorkContext scope() {
    var item = items.selectById(itemId);
    return new ElectronicDrawingWorkContext(itemId, 0, null, null, formId, itemId, "EDP-05", oaNo,
        material, null, item.getProductName(), item.getSpec(), item.getSunlModel(), "NON_BARE", "FULL_BOM",
        month, "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "NEED_TECH", null, null, null, null);
  }
}
