package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.mapper.*;
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
class DrawingResultConsumptionIntegrationTest extends com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void pricingMonth(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL", CostPricingPeriodUtils::currentPricingMonth);
  }
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
  @Autowired ElectronicDrawingWorkflowOrchestrator workflow;
  @Autowired com.sanhua.marketingcost.service.QuoteCostingWorkbenchService workbench;
  @Autowired ElectronicDrawingSourceImportService sourceImporter;
  @Autowired QuoteBomSupplementVersionMapper sourceVersions;
  @Autowired QuoteBomSupplementDetailMapper sourceDetails;
  @Autowired ElectronicDrawingSourceNodeRepository sourceNodes;
  @Autowired com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService effectiveBom;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalManufacturingInputs manufacturingInputs;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec technicalCodec;
  @Autowired QuoteTechDataVersionMapper technicalVersions;
  private boolean manufacturingFixture;
  @MockBean CurrentU9BomGateway u9;
  @MockBean DrawingVerificationClient client;
  protected String key, month;
  protected Long itemId;

  @Test
  void freshDrawingAlsoRetainsRealManufacturingSupplementInputs() {
    manufacturingFixture = true;
    freshMonthAndQuoteReuseCompleteApprovedBomAfterLatestRawDrawingComparison();
  }

  @Test
  void changedQuantityScalesCompleteBomButRetainsPerPartManufacturingInputs() {
    manufacturingFixture = true;
    freshMonthAndQuoteReuseCompleteApprovedBomAfterLatestRawDrawingComparison();
    var current = nextContext();
    var prior = jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", current.sourceVersionId());
    doAnswer(call -> {
      var body = payload("quantity-" + UUID.randomUUID());
      ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("data").path(key + "-DRAW-MX").path("params").path("items").get(0)).put("qty", "4");
      return codec.parse(body.toString());
    }).when(client).verify(any());
    var task = verifications.restart(current, null, verifications.current(current).orElseThrow().getId()).orElseThrow();
    assertThat(consumer.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    var changed = nextContext();
    assertThat(changed.published()).isTrue();
    assertThat(jdbc.queryForList("SELECT qty_per_top FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND node_source_type='TECH_RAW' ORDER BY qty_per_top", BigDecimal.class, changed.sourceVersionId()))
        .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal(".001875"), new BigDecimal(".060"));
    assertThat(manufacturingInputs.byParentPath(changed.oaFormItemId(), changed.accountingMonth()).values())
        .hasSize(2).allSatisfy(input -> assertThat(input.material().quantityPerParent()).isEqualByComparingTo(".015"));
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", current.sourceVersionId())).isEqualTo(prior);
  }

  @Test
  void addedDrawingNodeDoesNotDiscardUnchangedManufacturingSupplements() {
    manufacturingFixture = true;
    freshMonthAndQuoteReuseCompleteApprovedBomAfterLatestRawDrawingComparison();
    var current = nextContext();
    material(key + "-NEW", key + "-NEW-DRAW", "采购件");
    doAnswer(call -> {
      var body = payload("addition-" + UUID.randomUUID());
      var rows = (com.fasterxml.jackson.databind.node.ArrayNode) body.path("data").path(key + "-DRAW-MX").path("params").path("items");
      var added = rows.get(0).deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) added).put("code", key + "-NEW-DRAW");
      rows.add(added);
      return codec.parse(body.toString());
    }).when(client).verify(any());
    var task = verifications.restart(current, null, verifications.current(current).orElseThrow().getId()).orElseThrow();
    var outcome = consumer.consume(task.getId());
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    var changed = nextContext();
    assertThat(changed.sourceVersionId()).isNotEqualTo(current.sourceVersionId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND node_source_type='TECH_RAW'", Integer.class, changed.sourceVersionId())).isEqualTo(2);
    assertThat(manufacturingInputs.byParentPath(changed.oaFormItemId(), changed.accountingMonth())).hasSize(2);
  }

  private ElectronicDrawingWorkContext nextContext() {
    Long nextItem = jdbc.queryForObject("SELECT i.id FROM oa_form_item i JOIN oa_form f ON f.id=i.oa_form_id WHERE f.oa_no=?", Long.class, key + "-NEXT");
    return contexts.load(nextItem, "COMMERCIAL", "210", CostPricingPeriodUtils.currentPricingMonth());
  }

  @Test
  void freshMonthAndQuoteReuseCompleteApprovedBomAfterLatestRawDrawingComparison() {
    String currentMonth = month;
    month = java.time.YearMonth.parse(month).minusMonths(1).toString();
    jdbc.update("UPDATE oa_form SET customer=?,accounting_period_month=? WHERE oa_no=?", key, month, key);
    jdbc.update("UPDATE lp_quote_bom_preparation_record SET cost_period_month=? WHERE oa_form_item_id=?", month, itemId);
    var oldTask = start();
    assertThat(consumer.consume(oldTask.getId()).consumptionState()).isEqualTo("COMPLETE");
    var oldContext = contexts.load(itemId, "COMMERCIAL", "210", month);
    Long oldSource = oldContext.sourceVersionId();
    var oldVersion = sourceVersions.selectById(oldSource);
    var savedNodes = sourceNodes.findByVersionId(oldSource);
    var savedDetails = sourceDetails.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .<com.sanhua.marketingcost.entity.QuoteBomSupplementDetail>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.QuoteBomSupplementDetail::getSupplementVersionId, oldSource));
    // 模拟已批准的完整树比原始图库多一项补录，而原始 PDF 结果始终不改写。
    var supplement = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
    org.springframework.beans.BeanUtils.copyProperties(savedDetails.stream().filter(row -> row.getLevel() == 1).findFirst().orElseThrow(), supplement, "id");
    supplement.setLineNo(99); supplement.setPath("/" + key + "/SUPPLEMENT/");
    supplement.setQtyPerParent(new BigDecimal("7")); supplement.setQtyPerTop(new BigDecimal("7"));
    supplement.setNodeSourceType("TECHNICAL_RAW"); supplement.setManualFlag(1);
    sourceDetails.insert(supplement);
    int manufacturingRows = manufacturingFixture ? installManufacturing(oldContext, savedDetails) : 0;
    oldVersion.setVersionStatus("APPROVED"); oldVersion.setReviewedAt(java.time.LocalDateTime.now());
    oldVersion.setReuseValidUntil(java.time.YearMonth.parse(month).plusMonths(6).atEndOfMonth());
    sourceVersions.updateById(oldVersion);
    contexts.completePublication(oldContext, oldVersion.getCompositionFingerprint(), java.time.LocalDateTime.now());
    var oldRows = jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", oldSource);
    var originalEvidence = results.findById(oldTask.getCurrentResultId()).getRawSummaryJson();

    String nextOa = key + "-NEXT";
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type,customer) VALUES(?,?,'COMMERCIAL',?)", nextOa, currentMonth, key);
    Long nextForm = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, nextOa);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,sunl_model,product_name,business_unit_type) VALUES(?,?,?,'热力膨胀阀','COMMERCIAL')", nextForm, key, key);
    Long nextItem = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, nextForm);
    var nextTask = verifications.start(contexts.load(nextItem, "COMMERCIAL", "210", currentMonth), null).orElseThrow();
    var outcome = consumer.consume(nextTask.getId());
    assertThat(outcome.consumptionState()).isEqualTo("COMPLETE");
    var adopted = contexts.load(nextItem, "COMMERCIAL", "210", currentMonth);
    assertThat(adopted.published()).as("相同图库必须沿用完整批准BOM，不再要求补录").isTrue();
    var nextVersion = sourceVersions.selectById(adopted.sourceVersionId());
    assertThat(nextVersion.getReusedFromVersionId()).isEqualTo(oldSource);
    assertThat(nextVersion.getDrawingResultId()).isEqualTo(nextTask.getCurrentResultId());
    assertThat(nextVersion.getDrawingResultId()).isNotEqualTo(oldTask.getCurrentResultId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?", Integer.class, nextVersion.getId()))
        .isEqualTo(savedDetails.size() + 1 + manufacturingRows);
    assertThat(jdbc.queryForObject("SELECT qty_per_top FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND path=?", BigDecimal.class,
        nextVersion.getId(), supplement.getPath())).isEqualByComparingTo("7");
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? ORDER BY id", oldSource)).isEqualTo(oldRows);
    assertThat(results.findById(oldTask.getCurrentResultId()).getRawSummaryJson()).isEqualTo(originalEvidence);
    assertThat(sourceNodes.findByVersionId(oldSource)).hasSize(savedNodes.size());
    var tree = effectiveBom.getEffectiveBom(nextOa, nextItem);
    assertThat(tree.nodes()).as("普通工作台必须读到完整BOM，不能再报先取得图库明细").isNotEmpty();
    assertThat(tree.blockIssues()).noneMatch(issue -> issue.issueCode().contains("TECH_DATA_SOURCE_NOT_CONFIRMED"));
    if (manufacturingFixture) {
      var inputs = manufacturingInputs.byParentPath(nextItem, currentMonth);
      assertThat(inputs).hasSize(2);
      assertThat(inputs.values()).allSatisfy(input -> {
        assertThat(input.material().grossWeightKg()).isEqualByComparingTo(".015");
        assertThat(input.material().netWeightKg()).isEqualByComparingTo(".010");
        assertThat(input.material().netLengthMm()).isEqualByComparingTo("120");
      });
    }
    verify(client, times(2)).verify(any());
  }

  private int installManufacturing(ElectronicDrawingWorkContext context,
      List<com.sanhua.marketingcost.entity.QuoteBomSupplementDetail> rows) {
    jdbc.update("INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,assignee_user_id,assignee_name,task_status,active_flag) VALUES(?,?,?,?,'COMMERCIAL','210',1,'Test','APPROVED',0)", key, context.oaFormId(), key, month);
    Long task = jdbc.queryForObject("SELECT id FROM lp_quote_tech_task WHERE task_no=?", Long.class, key);
    jdbc.update("INSERT INTO lp_quote_tech_product(task_id,oa_form_item_id,material_no,quote_no,accounting_month,source_snapshot_json,source_fingerprint,product_status,active_flag) VALUES(?,?,?,?,?,'{}',REPEAT('a',64),'APPROVED',0)", task, itemId, key, key, month);
    Long product = jdbc.queryForObject("SELECT id FROM lp_quote_tech_product WHERE task_id=?", Long.class, task);
    var version = new com.sanhua.marketingcost.entity.QuoteTechDataVersion();
    version.setProductId(product); version.setVersionNo(1); version.setVersionStatus("DRAFT"); version.setContentSchemaVersion(2);
    technicalVersions.insert(version);
    material(key + "-RAW", key + "-RAW-DRAW", "采购件");
    jdbc.update("UPDATE lp_material_master_raw SET unit='kg' WHERE material_code=?", key + "-RAW");
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='制造件' WHERE material_code=?", key + "-PART");
    var inputs = new java.util.ArrayList<com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial>();
    for (var parent : rows.stream().filter(row -> "E_DRAWING".equals(row.getNodeSourceType())
        && row.getSourceElectronicNodeId() != null).toList()) {
      String itemKey = "RAW:" + parent.getSourceElectronicNodeId();
      var input = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial(
          itemKey, parent.getSourceElectronicNodeId().toString(), context.sourceVersionId(), parent.getMaterialCode(),
          key + "-RAW", key + "-RAW-DRAW", new BigDecimal(".010"), new BigDecimal(".015"), "kg", null,
          new BigDecimal(".015"), new BigDecimal("120"),
          new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ManufacturingNodeEvidence(
              "Test", parent.getDrawingNo(), parent.getQtyPerParent(), new BigDecimal("10"), "g", "铜棒", null, "NO_SCRAP_CONFIRMED", List.of()));
      inputs.add(input);
      var raw = new com.sanhua.marketingcost.entity.QuoteBomSupplementDetail();
      org.springframework.beans.BeanUtils.copyProperties(parent, raw, "id");
      raw.setLineNo(100 + inputs.size()); raw.setLevel(parent.getLevel() + 1);
      raw.setPath(parent.getPath() + "TECH:" + version.getId() + ":" + itemKey + "/");
      raw.setParentCode(parent.getMaterialCode()); raw.setMaterialCode(key + "-RAW"); raw.setUnit("kg");
      raw.setQtyPerParent(input.quantityPerParent()); raw.setQtyPerTop(parent.getQtyPerTop().multiply(input.quantityPerParent()));
      raw.setNodeSourceType("TECH_RAW"); raw.setShapeAttr("采购件"); raw.setManualFlag(1);
      sourceDetails.insert(raw);
      parent.setShapeAttr("制造件"); sourceDetails.updateById(parent);
    }
    version.setManufacturingJson(technicalCodec.manufacturingJson(
        new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing(inputs, null)));
    version.setReferenceSnapshotJson(technicalCodec.referenceSnapshotJson(List.of()));
    version.setSourceFactsJson("{\"productSourceJson\":\"{}\",\"modules\":[]}");
    version.setContentFingerprint(technicalCodec.fingerprint(version, List.of(), List.of(), List.of(), List.of()));
    technicalVersions.updateById(version);
    for (String status : List.of("FROZEN", "SUBMITTED", "APPROVED"))
      jdbc.update("UPDATE lp_quote_tech_data_version SET version_status=? WHERE id=?", status, version.getId());
    return inputs.size();
  }

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
  void importedSourceResumesWithoutReacquiringDrawingWhenLocalProductMasterIsMissing() {
    var task = start();
    sourceImporter.importSource(contexts.load(itemId, "COMMERCIAL", "210", month), task.getCurrentResultId());
    jdbc.update("DELETE FROM lp_material_master_raw WHERE material_code=?", key);
    var context = contexts.load(itemId, "COMMERCIAL", "210", month);
    assertThat(verifications.importedForContinuation(context)).isPresent();
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "unused", List.of());
    authentication.setDetails(java.util.Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse prepared;
    try { prepared = workbench.launchWorkbench(key, itemId); }
    finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    context = contexts.load(itemId, "COMMERCIAL", "210", month);
    assertThat(context.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    assertThat(prepared.getBomRows()).isNotEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master_raw WHERE material_code=?", Integer.class, key)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND resolved_material_code IS NOT NULL", Integer.class, context.sourceVersionId())).isEqualTo(2);
    verify(client, times(1)).verify(any());
  }

  @Test
  void importedResultCannotResumeAfterQuoteInputChangesOrWhenU9HasABom() {
    var task = start();
    var context = contexts.load(itemId, "COMMERCIAL", "210", month);
    sourceImporter.importSource(context, task.getCurrentResultId());
    context = contexts.load(itemId, "COMMERCIAL", "210", month);
    assertThat(verifications.importedForContinuation(context)).isPresent();
    jdbc.update("UPDATE oa_form_item SET sunl_model='CHANGED' WHERE id=?", itemId);
    assertThat(verifications.importedForContinuation(context)).isEmpty();
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", key, itemId);
    when(u9.read(any())).thenThrow(new IllegalStateException("U9 unavailable"));
    var savedContext = context;
    assertThatThrownBy(() -> verifications.importedForContinuation(savedContext)).hasMessageContaining("U9 unavailable");
    verify(client, times(1)).verify(any());
  }

  @Test
  void costingEntryContinuesReceivedSourceThroughMatching() {
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
        "admin", "unused", List.of());
    authentication.setDetails(java.util.Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    jdbc.update("DELETE FROM lp_quote_bom_preparation_record WHERE oa_form_item_id=?", itemId);
    com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse result;
    try { result = workbench.launchWorkbench(key, itemId); }
    finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    var context = contexts.load(itemId, "COMMERCIAL", "210", month);
    assertThat(context.sourceVersionId()).isNotNull();
    assertThat(context.workflowStage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND resolved_material_code IS NOT NULL", Integer.class, context.sourceVersionId())).isEqualTo(2);
    assertThat(result.getBomRows()).isNotEmpty();
    verify(client, times(1)).verify(any());
  }

  @Test
  void firstReceivedResultCreatesPreparationAndContinuesMatchingInTheSameCall() {
    jdbc.update("DELETE FROM lp_quote_bom_preparation_record WHERE oa_form_item_id=?", itemId);
    var task = start();
    var result = workflow.process(new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
        itemId, itemId, "COMMERCIAL", "210", task.getDrawingNo(), null, "财务报价", month));
    assertThat(result.sourceVersionId()).isNotNull();
    assertThat(result.stage()).isEqualTo(ElectronicDrawingWorkflowStage.COMPOSED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND resolved_material_code IS NOT NULL", Integer.class, result.sourceVersionId())).isEqualTo(2);
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
  void noMaterialQuoteUsesLookupPriorityForVerificationAndKeepsItsQuotedIdentityAsBomRoot(String specification) {
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
        .isEqualTo(quoteCode);
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
              properties, codec, new com.fasterxml.jackson.databind.ObjectMapper(),
              new com.sanhua.marketingcost.integration.drawing.DrawingVerificationCallGuard(jdbc.getDataSource()));
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

  protected com.fasterxml.jackson.databind.node.ObjectNode payload(String id) {
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
