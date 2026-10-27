package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@AutoConfigureMockMvc
class DrawingResultConsumptionIntegrationTest extends BomMapperTestBase {
  @Autowired MockMvc mvc;
  @Autowired DrawingResultConsumptionService consumer;
  @Autowired DrawingVerificationService verifications;
  @Autowired DrawingReviewService reviews;
  @Autowired DrawingVerificationCodec codec;
  @Autowired ElectronicDrawingWorkflowContextPort contexts;
  @Autowired DrawingVerificationTaskMapper tasks;
  @Autowired DrawingVerificationResultMapper results;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @Autowired JdbcTemplate jdbc;
  @MockBean CurrentU9BomGateway u9;
  @MockBean DrawingVerificationClient client;
  private String key, month;
  private Long itemId;

  @BeforeEach
  void fixture() {
    key = "EDP06-" + UUID.randomUUID().toString().substring(0, 12);
    month = CostPricingPeriodUtils.currentPricingMonth();
    jdbc.update(
        "INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type)"
            + " VALUES(?,?,'COMMERCIAL')",
        key,
        month);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,sunl_model,product_name,customer_drawing,business_unit_type)"
            + " VALUES(?,?,?,'热力膨胀阀',?,'COMMERCIAL')",
        formId,
        key,
        key,
        key + "-DRAW-MX");
    itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    material(key, key + "-DRAW-MX", "制造件");
    material(key + "-PART", key + "-PART-DRAW", "采购件");
    var preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId);
    preparation.setOaFormItemId(itemId);
    preparation.setOaNo(key);
    preparation.setQuoteProductCode(key);
    preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setCostPeriodMonth(month);
    preparation.setPreparationStatus("NEED_TECH");
    preparation.setActiveFlag(1);
    preparation.setElectronicWorkflowVersion(0);
    preparations.insert(preparation);
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    doAnswer(call -> codec.parse(payload("result-" + UUID.randomUUID()).toString()))
        .when(client)
        .verify(any());
  }

  @Test
  void closedOaKeepsReceivedHistoryWithoutGeneratingBom() {
    var task = start();
    Long formId =
        jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?", Long.class, itemId);
    jdbc.update(
        "INSERT INTO"
            + " lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version)"
            + " VALUES('EDP06','TEST',?,?,1)",
        key,
        formId);
    jdbc.update(
        "INSERT INTO"
            + " lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json)"
            + " VALUES('EDP06','TEST',?,1,1,1,'CANCELLED','[]')",
        key);
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    assertThat(tasks.selectById(task.getId()).getErrorMessage()).contains("已取消");
    assertThat(results.findById(task.getCurrentResultId())).isNotNull();
    assertThat(sourceCount()).isZero();
    verify(client, times(1)).verify(any());
  }

  @Test
  void submittedCostKeepsReceivedHistoryWithoutGeneratingBom() {
    var task = start();
    Long formId =
        jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?", Long.class, itemId);
    jdbc.update(
        "INSERT INTO"
            + " lp_quote_final_submission(oa_form_id,oa_no,accounting_month,submission_round,source_system,environment,external_document_id,business_unit_type,status,content_fingerprint,cost_snapshot_json,operator_user_id,operator_external_id)"
            + " VALUES(?,?,?,1,'EDP06','TEST',?,'COMMERCIAL','SUBMITTED',REPEAT('a',64),'{}',1,'EDP06')",
        formId,
        key,
        month,
        key);
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    assertThat(tasks.selectById(task.getId()).getErrorMessage()).contains("成本已提交");
    assertThat(results.findById(task.getCurrentResultId())).isNotNull();
    assertThat(sourceCount()).isZero();
    verify(client, times(1)).verify(any());
  }

  @Test
  void zeroDifferencesImportMatchAndComposeFromStoredJsonWithoutSecondPost() {
    var task = start();
    var outcome = consumer.consume(task.getId());
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    assertThat(outcome.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    var source =
        jdbc.queryForMap(
            "SELECT * FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?",
            task.getCurrentResultId());
    assertThat(source.get("bom_source")).isEqualTo(ElectronicDrawingSourceKind.PDF);
    assertThat(source.get("source_file_name")).isNull();
    assertThat(source.get("source_file_sha256")).isNull();
    Long sourceId = ((Number) source.get("id")).longValue();
    var rows =
        jdbc.queryForList(
            "SELECT"
                + " source_sequence,qty,parent_source_sequence,reference_weight_unit,resolved_material_code"
                + " FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? ORDER BY"
                + " source_row_no",
            sourceId);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("parent_source_sequence")).isNull();
              assertThat(row.get("reference_weight_unit")).isNull();
              assertThat(row.get("resolved_material_code")).isEqualTo(key + "-PART");
            });
    var quantities =
        jdbc.queryForList(
            "SELECT qty_per_top FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?"
                + " AND material_code=? ORDER BY line_no",
            BigDecimal.class,
            sourceId,
            key + "-PART");
    assertThat(quantities)
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(new BigDecimal("2"), new BigDecimal("0.125"));
    assertThat(results.findById(task.getCurrentResultId()).getDataJson())
        .contains("2.0000", "0.1250", "001");
    var before =
        jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId());
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("SKIPPED");
    assertThat(
            jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId()))
        .isEqualTo(before);
    assertThat(sourceCount()).isEqualTo(1);
    verify(client, times(1)).verify(any());
  }

  @Test
  void manualResultKeepsInitialPlmAndIsTheOnlyImportedContent() {
    doAnswer(
            call -> {
              var body = payload("manual-" + UUID.randomUUID());
              body.put("differenceCount", 2);
              body.putObject("data");
              return codec.parse(body.toString());
            })
        .when(client)
        .verify(any());
    var task = start();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("SKIPPED");
    var confirmation = payload(task.getExternalTaskId());
    confirmation.put("status", "confirmed");
    confirmation.put("differenceCount", 2);
    confirmation.put("confirmedAt", 1792550100000L);
    confirmation.put("revision", 3);
    confirmation.put("deliveryStatus", "pendingRetry");
    confirmation.putArray("plm_data");
    var receipt = reviews.receive(codec.parse(confirmation.toString()));
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT drawing_result_id FROM lp_quote_bom_supplement_version WHERE"
                    + " oa_form_item_id=?",
                Long.class,
                itemId))
        .isEqualTo(receipt.resultId());
    assertThat(results.findById(receipt.resultId()).getPlmSourceResultId())
        .isEqualTo(task.getCurrentResultId());
    assertThat(results.findById(task.getCurrentResultId()).getPlmDataJson())
        .contains("V002", "V001");
    assertThat(
            reviews
                .receive(codec.parse(confirmation.put("deliveryAttempts", 5).toString()))
                .duplicate())
        .isTrue();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("SKIPPED");
    assertThat(sourceCount()).isEqualTo(1);
    verify(client, times(1)).verify(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPEC-A", "UNKNOWN", ""})
  void noMaterialQuoteUsesTheSamePriorityForVerificationAndTheActualBomRoot(String specification) {
    String quoteCode =
        com.sanhua.marketingcost.util.QuoteProductIdentityUtils.resolveCostingCode(
            null, key, "STALE-QUOTE-DRAW");
    jdbc.update(
        "UPDATE oa_form_item SET material_no=NULL,spec=?,customer_drawing='STALE-QUOTE-DRAW' WHERE"
            + " id=?",
        specification,
        itemId);
    jdbc.update(
        "UPDATE lp_quote_bom_preparation_record SET quote_product_code=? WHERE oa_form_item_id=?",
        quoteCode,
        itemId);
    jdbc.update(
        "UPDATE lp_material_master_raw SET material_spec='SPEC-A' WHERE material_code=?", key);
    jdbc.update(
        "UPDATE lp_material_master_raw SET material_model=? WHERE material_code=?",
        key + "-PART",
        key + "-PART");
    if ("SPEC-A".equals(specification)) {
      material(key + "-OTHER", key + "-DRAW-MX", "制造件");
      jdbc.update(
          "UPDATE lp_material_master_raw SET material_spec='SPEC-B' WHERE material_code=?",
          key + "-OTHER");
    }
    var task = start();
    var outcome = consumer.consume(task.getId());
    assertThat(task.getDrawingNo()).isEqualTo(key + "-DRAW-MX");
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    assertThat(outcome.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    Long sourceId =
        jdbc.queryForObject(
            "SELECT id FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?",
            Long.class,
            task.getCurrentResultId());
    assertThat(
            jdbc.queryForObject(
                "SELECT quote_product_code FROM lp_quote_bom_supplement_version WHERE id=?",
                String.class,
                sourceId))
        .isEqualTo(quoteCode);
    assertThat(
            jdbc.queryForObject(
                "SELECT material_code FROM lp_quote_bom_supplement_detail WHERE"
                    + " supplement_version_id=? AND level=0",
                String.class,
                sourceId))
        .isEqualTo(key);
    verify(client, times(1)).verify(argThat(query -> (key + "-DRAW-MX").equals(query.drawingNo())));
  }

  @Test
  void rollbackIsRecoverableFromSameResultAndNeverRequeriesPdf() {
    var task = start();
    jdbc.execute(
        "CREATE TRIGGER edp06_fail_source BEFORE INSERT ON lp_quote_bom_supplement_version FOR EACH"
            + " ROW BEGIN IF NEW.bom_source='ELECTRONIC_DRAWING_PDF' THEN SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='controlled import failure'; END IF; END");
    try {
      assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    } finally {
      jdbc.execute("DROP TRIGGER edp06_fail_source");
    }
    assertThat(sourceCount()).isZero();
    assertThat(results.findById(task.getCurrentResultId())).isNotNull();
    assertThat(tasks.findPendingConsumption(100))
        .noneMatch(row -> row.getId().equals(task.getId()));
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    assertThat(sourceCount()).isEqualTo(1);
    verify(client, times(1)).verify(any());
  }

  @Test
  void u9AvailableOrErrorNeverImportsDrawing() {
    var task = start();
    when(u9.read(any())).thenReturn(CurrentU9BomResult.error("not absence"));
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    assertThat(sourceCount()).isZero();
    when(u9.read(any())).thenReturn(CurrentU9BomResult.available("U9", "V1", "batch", 2));
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("U9_BOM_AVAILABLE");
    assertThat(tasks.selectById(task.getId()).getConsumptionState()).isEqualTo("NOT_READY");
    assertThat(sourceCount()).isZero();
    verify(client, times(1)).verify(any());
  }

  @Test
  void compositionFailureRetriesWithTheAlreadyImportedSource() {
    var task = start();
    jdbc.execute(
        "CREATE TRIGGER edp06_fail_bom BEFORE INSERT ON lp_quote_bom_supplement_detail FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled composition failure'");
    try {
      assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    } finally {
      jdbc.execute("DROP TRIGGER edp06_fail_bom");
    }
    assertThat(sourceCount()).isEqualTo(1);
    assertThat(tasks.selectById(task.getId()).getConsumedResultId())
        .isEqualTo(task.getCurrentResultId());
    var recovered = consumer.consume(task.getId());
    assertThat(recovered.consumptionState()).isEqualTo("COMPLETE");
    assertThat(recovered.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE oa_form_item_id=?",
                Integer.class,
                itemId))
        .isGreaterThan(0);
    assertThat(sourceCount()).isEqualTo(1);
    verify(client, times(1)).verify(any());
  }

  @Test
  void changedQuoteIsPreservedAsHistoryWithoutStartingNewVerification() {
    var task = start();
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", key + "-CHANGED", itemId);
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("SUPERSEDED");
    assertThat(tasks.selectById(task.getId()).getActiveScopeKey()).isNull();
    assertThat(results.findHistory(task.getId())).hasSize(1);
    assertThat(sourceCount()).isZero();
    verify(client, times(1)).verify(any());
  }

  @Test
  void concurrentConsumersCreateOneSourceAndOneBom() throws Exception {
    var task = start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> consumer.consume(task.getId()));
      var second = executor.submit(() -> consumer.consume(task.getId()));
      assertThat(
              List.of(
                  first.get(15, TimeUnit.SECONDS).consumptionState(),
                  second.get(15, TimeUnit.SECONDS).consumptionState()))
          .containsExactlyInAnyOrder("COMPLETE", "SKIPPED");
    }
    assertThat(sourceCount()).isEqualTo(1);
    verify(client, times(1)).verify(any());
  }

  @Test
  void laterManualResultCreatesNewSourceWithoutChangingPriorApprovedSourceOrCost() {
    var task = start();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    Long oldSource =
        jdbc.queryForObject(
            "SELECT id FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?",
            Long.class,
            task.getCurrentResultId());
    jdbc.update(
        "UPDATE lp_quote_bom_supplement_version SET version_status='APPROVED' WHERE id=?",
        oldSource);
    jdbc.update(
        "INSERT INTO"
            + " lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,business_unit_type)"
            + " VALUES(?,?,?,?,?,?,'SUCCESS','COMMERCIAL')",
        key,
        key,
        itemId,
        key,
        month,
        month);
    var oldVersion =
        jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE id=?", oldSource);
    var oldNodes =
        jdbc.queryForList(
            "SELECT * FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? ORDER BY"
                + " id",
            oldSource);
    var oldCost =
        jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?", key);
    var body = payload(task.getExternalTaskId());
    body.put("status", "confirmed");
    body.put("confirmedAt", 1792550100000L);
    body.put("revision", 3);
    body.putArray("plm_data");
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            body.path("data").path(key + "-DRAW-MX").path("params").path("items").get(0))
        .put("qty", "4.0000");
    var confirmed = reviews.receive(codec.parse(body.toString()));
    var outcome = consumer.consume(task.getId());
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    assertThat(outcome.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    Long newSource =
        jdbc.queryForObject(
            "SELECT id FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?",
            Long.class,
            confirmed.resultId());
    assertThat(newSource).isNotEqualTo(oldSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(qty) FROM lp_electronic_drawing_source_node WHERE"
                    + " supplement_version_id=?",
                BigDecimal.class,
                newSource))
        .isEqualByComparingTo("4.125");
    assertThat(
            jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE id=?", oldSource))
        .isEqualTo(oldVersion);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM lp_electronic_drawing_source_node WHERE supplement_version_id=?"
                    + " ORDER BY id",
                oldSource))
        .isEqualTo(oldNodes);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?", key))
        .isEqualTo(oldCost);
    verify(client, times(1)).verify(any());
  }

  @Test
  void unknownWeightUnitBlocksWeightBasedBomWithoutGuessingOrPublishing() {
    jdbc.update("UPDATE lp_material_master_raw SET unit='kg' WHERE material_code=?", key + "-PART");
    var task = start();
    var outcome = consumer.consume(task.getId());
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    assertThat(outcome.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.VALIDATION_FAILED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE oa_form_item_id=?",
                Integer.class,
                itemId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT version_status FROM lp_quote_bom_supplement_version WHERE"
                    + " drawing_result_id=?",
                String.class,
                task.getCurrentResultId()))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForList(
                "SELECT reference_weight_unit FROM lp_electronic_drawing_source_node WHERE"
                    + " supplement_version_id=(SELECT id FROM lp_quote_bom_supplement_version WHERE"
                    + " drawing_result_id=?)",
                String.class,
                task.getCurrentResultId()))
        .containsOnlyNulls();
    verify(client, times(1)).verify(any());
  }

  @Test
  void invalidQuantityKeepsCompleteRawEvidenceWithoutCreatingZeroQuantityRows() {
    doAnswer(
            call -> {
              var body = payload("invalid-" + UUID.randomUUID());
              ((com.fasterxml.jackson.databind.node.ObjectNode)
                      body.path("data").path(key + "-DRAW-MX").path("params").path("items").get(0))
                  .put("qty", "not-a-number");
              return codec.parse(body.toString());
            })
        .when(client)
        .verify(any());
    var task = start();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("FAILED");
    assertThat(sourceCount()).isZero();
    assertThat(results.findById(task.getCurrentResultId()).getDataJson())
        .contains("not-a-number", "0.1250");
    verify(client, times(1)).verify(any());
  }

  @Test
  void otherDrawingGroupsRemainInEvidenceAndNeverMixIntoThisProductsBom() {
    doAnswer(
            call -> {
              var body = payload("groups-" + UUID.randomUUID());
              ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("data"))
                  .set(
                      "OTHER-PRODUCT",
                      DrawingVerificationFixtures.initial("unused").path("data").path("DRAW-02"));
              return codec.parse(body.toString());
            })
        .when(client)
        .verify(any());
    var task = start();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    assertThat(results.findById(task.getCurrentResultId()).getDataJson())
        .contains("OTHER-PRODUCT", "PART-002");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE"
                    + " supplement_version_id=(SELECT id FROM lp_quote_bom_supplement_version WHERE"
                    + " drawing_result_id=?)",
                Integer.class,
                task.getCurrentResultId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE oa_form_item_id=? AND"
                    + " drawing_no='PART-002'",
                Integer.class,
                itemId))
        .isZero();
  }

  private DrawingVerificationTask start() {
    return verifications
        .start(contexts.load(itemId, "COMMERCIAL", "210", month), null)
        .orElseThrow();
  }

  @Test
  void actualHttpVerificationCallbackAndSourceLogsShareTheSameTask() throws Exception {
    var id = "http-chain-" + UUID.randomUUID();
    var initial = payload(id);
    initial.put("differenceCount", 2);
    initial.putObject("data");
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var logged =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logged.start();
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog.class);
    logger.addAppender(logged);
    server.createContext(
        "/api/drawings/verify",
        exchange -> {
          calls.incrementAndGet();
          var request =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          assertThat(request)
              .isEqualTo(
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .createObjectNode()
                      .put("drawingNo", key + "-DRAW-MX")
                      .toString());
          byte[] body = initial.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var properties = new DrawingVerificationProperties();
      properties.setEnabled(true);
      properties.setBaseUrl(
          java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      properties.setRequestTimeout(java.time.Duration.ofSeconds(5));
      var actualClient =
          new HttpDrawingVerificationClient(
              properties, codec, new com.fasterxml.jackson.databind.ObjectMapper());
      doAnswer(call -> actualClient.verify(call.getArgument(0))).when(client).verify(any());
      var task = start();
      var confirmed = payload(id);
      confirmed.put("status", "confirmed");
      confirmed.put("confirmedAt", 1792550100000L);
      confirmed.put("differenceCount", 2);
      confirmed.put("revision", 3);
      confirmed.put("deliveryStatus", "pendingRetry");
      confirmed.putArray("plm_data");
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      DrawingReviewController.PATH)
                  .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                  .content(confirmed.toString())
                  .header("X-Review-Task", id)
                  .header("Idempotency-Key", id)
                  .header("X-Request-ID", "trace-" + id))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
      assertThat(calls.get()).isEqualTo(1);
      assertThat(logged.list).hasSize(2);
      var events =
          logged.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .toList();
      assertThat(events)
          .allSatisfy(
              event ->
                  assertThat(event).contains(id).doesNotContain("secret-fixture", "old-fixture"));
      String directory = System.getProperty("edp.evidence.dir");
      if (directory != null) {
        var target = java.nio.file.Path.of(directory);
        java.nio.file.Files.createDirectories(target);
        java.nio.file.Files.write(target.resolve("http-callback-source-exchanges.jsonl"), events);
        var saved = tasks.selectById(task.getId());
        Long source =
            jdbc.queryForObject(
                "SELECT id FROM lp_quote_bom_supplement_version WHERE drawing_result_id=?",
                Long.class,
                saved.getCurrentResultId());
        java.nio.file.Files.writeString(
            target.resolve("http-callback-source-association.json"),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(
                    java.util.Map.of(
                        "localTaskId",
                        task.getId(),
                        "externalTaskId",
                        id,
                        "initialResultId",
                        task.getCurrentResultId(),
                        "confirmedResultId",
                        saved.getCurrentResultId(),
                        "sourceVersionId",
                        source,
                        "requestId",
                        task.getRequestId(),
                        "httpCalls",
                        calls.get())));
      }
    } finally {
      server.stop(0);
      logger.detachAppender(logged);
      logged.stop();
    }
  }

  private int sourceCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_bom_supplement_version WHERE oa_form_item_id=?",
        Integer.class,
        itemId);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode payload(String id) {
    return DrawingVerificationFixtures.drawing(
        id,
        key + "-DRAW-MX",
        List.of(
            List.of("001", key + "-PART-DRAW", "接管", "2.0000", "铜", "B", "B", "0.50", ""),
            List.of("001", key + "-PART-DRAW", "接管", "0.1250", "铜", "B", "B", "0.50", "")));
  }

  private void material(String code, String drawing, String shape) {
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,material_model,drawing_no,shape_attr,unit,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','热力膨胀阀',?,?,?,'只',1,'U9_API',?)",
        code,
        key,
        drawing,
        shape,
        key);
  }
}
