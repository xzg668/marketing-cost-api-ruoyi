package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingSaveRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataManufacturingIntegrationTest extends com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataManufacturingApplicationService service;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataAdminDirectSave adminDirect;
  @Autowired private TechnicalDataCostingSources costingSources;
  @Autowired private TechnicalDataSharedModuleRepository sharedSources;
  @Autowired private com.sanhua.marketingcost.service.costing.QuoteCostingSubmissionService costingSubmissions;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository sourceNodes;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechModuleMapper moduleMapper;
  @Autowired private com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort contexts;
  @Autowired private TechnicalDataManufacturingBomSource manufacturingBom;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper drawingVersions;
  @Autowired private com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionService resolution;
  @Autowired private com.sanhua.marketingcost.service.MakePartPricePrepareStrategy monthlyManufacturing;
  @Autowired private com.sanhua.marketingcost.mapper.BomCostingRowMapper costingRows;
  @MockBean private ElectronicDrawingU9SubBomPort u9;
  private Long taskId, productId, itemId, sourceId;
  private String key;

  @Autowired private TechnicalDataPriceApplicationService priceEntry;
  @Autowired private com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository oaRecipients;

  @Test void missingPricesStayInTheManufacturingTechniciansOpenTodoAndCanBeFrozenTogether() {
    jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',REPEAT('b',64),'COMMERCIAL')", key);
    Long messageId = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, key);
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id) VALUES(?,1,101,'王工','oa-tech-1','[\"DRAWING_BOM\",\"MANUFACTURING\"]',?,'TEST-TODO','CONFIRMED','OPEN',1,'oa-leader-1')", taskId, messageId);
    jdbc.update("UPDATE lp_quote_tech_task SET oa_assignment_version=1,external_task_status='PUBLISHED' WHERE id=?", taskId);
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=NULL,assignee_name=NULL,oa_edit_allowed=0 WHERE product_id=? AND module_type='PRICE'", productId);
    service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    assertThat(oaRecipients.current(taskId)).singleElement().satisfies(person -> {
      assertThat(person.modules()).containsExactly("DRAWING_BOM", "MANUFACTURING", "PRICE");
      assertThat(person.processingModules()).contains("PRICE");
      assertThat(person.externalTaskId()).isEqualTo("TEST-TODO");
    });
    assertThat(priceEntry.get(productId, null, LI).editable()).isFalse();
    completePrice(WANG);
    confirmC();
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    assertThat(priceEntry.get(productId, null, WANG).moduleStatus()).isEqualTo("READY");
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), oaRecipients.current(taskId).getFirst(), saved.expectedVersion(), 101L);
    assertThat(codec.prices(frozen).items()).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_integration_message WHERE request_id=?", Integer.class, key)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, key + "-RAW")).isZero();
  }

  @Test void savedRawMaterialOpensMissingPriceForTheSameTechnicianBeforeTheWholeBomIsComplete() {
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    assertThat(saved.issues()).isNotEmpty(); // 另一制造件尚待料号确认，不妨碍已知原材料补价。
    var price = repository.lockModules(productId).stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow();
    assertThat(price.getRequiredFlag()).isEqualTo(1);
    assertThat(price.getAssigneeUserId()).isEqualTo(WANG.userId());
    var response = priceEntry.get(productId, null, WANG);
    assertThat(response.editable()).isTrue();
    assertThat(response.requirements().issues()).isEmpty();
    assertThat(response.requirements().items()).filteredOn(row -> row.requiresEntry())
        .extracting(row -> row.materialNo()).containsExactly(key + "-RAW");
    completePrice(WANG);
    assertThat(priceEntry.get(productId, null, WANG).issues()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void onlyActuallyMissingRawAndScrapPricesNeedEntry(boolean rawPriced, boolean scrapPriced) {
    String raw = key + "-RAW", scrap = key + "-SCRAP";
    material(scrap, key + "-DS", "采购件", "kg");
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type,created_at,updated_at) VALUES(?,?,'kg',1,'COMMERCIAL',NOW(),NOW())", raw, scrap);
    if (rawPriced) jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,'raw','kg',66,0,'TEST','2026-09-01','2027-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", raw);
    if (scrapPriced) jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL','2026-09',?,'scrap','FIXED',5,'{}','IMPORT','2026-09',?,'TEST',NOW(),'TEST',NOW())", scrap, "f".repeat(64));
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    var price = moduleMapper.selectByTaskId(taskId).stream().filter(row -> "PRICE".equals(row.getModuleType())).findFirst().orElseThrow();
    assertThat(price.getRequiredFlag()).isEqualTo(rawPriced && scrapPriced ? 0 : 1);
    if (!rawPriced || !scrapPriced) {
      var response = priceEntry.get(productId, null, WANG);
      assertThat(response.requirements().issues()).isEmpty();
      assertThat(response.requirements().items()).filteredOn(row -> row.requiresEntry())
          .extracting(row -> row.materialNo()).containsExactlyInAnyOrderElementsOf(
              java.util.stream.Stream.of(rawPriced ? null : raw, scrapPriced ? null : scrap).filter(java.util.Objects::nonNull).toList());
    }
    assertThat(saved.priceRequirements()).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?)", Integer.class, raw, scrap)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_submission WHERE task_id=?", Integer.class, taskId)).isZero();
  }

  private void completePrice(TechnicalDataActor actor) {
    var current = priceEntry.get(productId, null, actor);
    if (!current.editable()) return;
    var request = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest();
    request.setExpectedVersion(current.expectedVersion()); request.setRequirementsFingerprint(current.requirements().fingerprint());
    request.setItems(current.requirements().items().stream().filter(row -> row.requiresEntry()).map(row -> {
      var input = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest.Item();
      input.setItemKey(row.itemKey()); input.setEntryMode("FIXED"); input.setUnitPrice(BigDecimal.TEN); return input;
    }).toList());
    if (!request.getItems().isEmpty()) assertThat(priceEntry.save(productId, request, actor).issues()).isEmpty();
  }

  @Test void adminDirectSaveValidatesFreezesAndSuppliesCostingWithoutOa() {
    jdbc.update("UPDATE lp_quote_tech_task SET assignee_user_id=NULL,assignee_name=NULL,task_status='UNASSIGNED' WHERE id=?", taskId);
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=NULL,assignee_name=NULL WHERE product_id=?", productId);
    jdbc.update("UPDATE lp_quote_tech_module SET required_flag=0,module_status='NOT_REQUIRED',source_availability='AVAILABLE' WHERE product_id=? AND module_type='PROFILE'", productId);
    var first = service.read(productId, null, ADMIN);
    assertThat(first.editable()).isTrue();
    assertThatThrownBy(() -> adminDirect.save(taskId, new TechnicalDataAdminDirectSave.Request(null), ADMIN))
        .hasMessageContaining("当前产品版本");
    var incomplete = adminDirect.save(taskId, new TechnicalDataAdminDirectSave.Request(first.expectedVersion()), ADMIN);
    assertThat(incomplete.effectiveVersionId()).isNull(); assertThat(incomplete.validation().valid()).isFalse();
    confirmC();
    var saved = service.save(productId, request(service.read(productId, null, ADMIN), true), ADMIN);
    assertThat(saved.issues()).isEmpty();
    assertThat(service.read(productId, null, ADMIN).saved()).isEqualTo(saved.saved());
    completePrice(ADMIN);
    var result = adminDirect.save(taskId, new TechnicalDataAdminDirectSave.Request(repository.findProduct(productId).orElseThrow().getRowVersion()), ADMIN);
    assertThat(result.effectiveVersionId()).isNotNull();
    var effective = repository.findVersion(result.effectiveVersionId()).orElseThrow();
    assertThat(effective.getVersionStatus()).isEqualTo("SUBMITTED");
    assertThat(effective.getUpdatedBy()).isEqualTo(1L);
    assertThat(codec.manufacturing(effective)).isEqualTo(saved.saved());
    assertThat(service.read(productId, null, ADMIN).editable()).isFalse();
    assertThat(service.read(productId, null, ADMIN).saved()).isEqualTo(saved.saved());
    var product = repository.findProduct(productId).orElseThrow();
    var task = repository.findTask(taskId).orElseThrow();
    assertThat(TechnicalDataAdminDirectSave.isPublished(task)).isTrue();
    assertThat(costingSubmissions.hasPendingSubmissions(task.getOaFormId(), itemId)).isFalse();
    costingSubmissions.requireAccepted(task.getOaFormId(), itemId);
    var now = LocalDateTime.now();
    var context = new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(task.getOaFormId(), itemId, key,
        "2026-09", "COMMERCIAL", key, null, null, null, "210", "COMMERCIAL", now.toLocalDate(), now);
    var selected = costingSources.requireSource(context, sharedSources.module(productId, "MANUFACTURING"));
    assertThat(selected.version().getId()).isEqualTo(effective.getId());
    assertThat(manufacturingBom.load(contexts.load(itemId, "COMMERCIAL", "210", "2026-09"))).isNotEmpty()
        .allSatisfy(row -> assertThat(row.technicalVersionId()).isEqualTo(effective.getId()));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_submission WHERE task_id=?", Integer.class, taskId)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_oa_recipient WHERE task_id=?", Integer.class, taskId)).isZero();
    assertThat(adminDirect.save(taskId, new TechnicalDataAdminDirectSave.Request(saved.expectedVersion()), ADMIN).effectiveVersionId()).isEqualTo(effective.getId());
    var later = request(saved, true);
    later.setExpectedVersion(service.read(productId, null, ADMIN).expectedVersion());
    assertThatThrownBy(() -> service.save(productId, later, ADMIN)).hasMessageContaining("未分派");
  }

  @Test void directSaveRejectsAssignedTasksAndNonAdminWithoutPublishingAnything() {
    var input = new TechnicalDataAdminDirectSave.Request(repository.findProduct(productId).orElseThrow().getRowVersion());
    assertThatThrownBy(() -> adminDirect.save(taskId, input, ADMIN)).hasMessageContaining("已分派");
    assertThatThrownBy(() -> adminDirect.save(taskId, input, WANG)).hasMessageContaining("只有管理员");
    assertThatThrownBy(() -> adminDirect.save(taskId, input,
        new TechnicalDataActor(1L, "OA", Set.of("*:*:*"), repository.findTask(taskId).orElseThrow().getOaFormId())))
        .hasMessageContaining("只有管理员");
    assertThat(repository.findProduct(productId).orElseThrow().getEffectiveVersionId()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=? AND version_status='SUBMITTED'", Integer.class, productId)).isZero();
  }

  @BeforeEach void fixture() throws Exception {
    key = "TW09-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,'2026-09','COMMERCIAL')", key);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,customer_drawing,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", formId, key, key);
    itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    var preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId); preparation.setOaFormItemId(itemId); preparation.setOaNo(key);
    preparation.setQuoteProductCode(key); preparation.setProductType("NON_BARE");
    preparation.setCostPeriodMonth("2026-09"); preparation.setPriceOrgCode("210"); preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setPreparationStatus("NEED_TECH"); preparation.setActiveFlag(1); preparation.setElectronicWorkflowVersion(0);
    preparations.insert(preparation);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(formId); task.setOaFormItemId(itemId);
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(itemId); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("DRAWING_BOM", "MANUFACTURING", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-NOT-FOUND");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("实际缺失原料关系");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    material(key, key, "制造件", "只"); material(key + "-A", key + "-DA", "制造件", "只");
    material(key + "-B", key + "-DB", "制造件", "只"); material(key + "-RAW", key + "-DR", "采购件", "kg");
    when(u9.query(any())).thenAnswer(call -> {
      SubBomQuery query = call.getArgument(0);
      if (!query.parentMaterialCode().equals(key + "-A")) return SubBomResult.failure(Status.NOT_FOUND, query.parentMaterialCode(), "无正式下级");
      return SubBomResult.available(key + "-A", "210", "COMMERCIAL", List.of(new U9Node("u1", null, 101L, 1001L,
          key + "-RAW", "已有原料", "T2", null, key + "-DR", "采购件", "111001008", "RAW", "RAW",
          "主制造", "V1", new BigDecimal("0.015"), BigDecimal.ONE, "kg", 1)));
    });
    sourceId = attachHistoricalDrawing();
  }

  @Test void partialSaveSurvivesFinanceResolutionAndComposesExactParentsWithoutMultiplyingSingleWeightTwice() {
    var first = service.read(productId, null, WANG);
    assertThat(first.source().nodes()).extracting(node -> node.state().name()).containsExactly("U9_READY", "MISSING_RAW", "MISSING_RAW", "WAIT_FINANCE");
    var partial = service.save(productId, request(first, true), WANG);
    assertThat(partial.priceRequirements()).hasSize(1).allSatisfy(row -> assertThat(row.status()).isEqualTo("MISSING_PRICE"));
    assertThat(partial.issues()).isNotEmpty(); assertThat(partial.bomComposed()).isFalse();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    confirmC();
    var current = service.read(productId, null, WANG);
    assertThat(current.saved()).isEqualTo(partial.saved());
    var saved = service.save(productId, request(current, true), WANG);
    assertThat(saved.source()).as("单纯保存原材料不能改动图库或 U9 来源").isEqualTo(current.source());
    assertThat(saved.issues()).isEmpty(); assertThat(saved.bomComposed()).isTrue();
    assertThat(saved.calculationInputs()).allSatisfy(row -> {
      assertThat(row.grossWeightG()).isEqualByComparingTo("12"); assertThat(row.netWeightG()).isEqualByComparingTo("10");
      assertThat(row.netLengthMm()).isEqualByComparingTo("120"); assertThat(row.purchasingQuantity()).isEqualByComparingTo("0.012");
    });
    var rows = jdbc.queryForList("SELECT parent_code,path,source_electronic_node_id,qty_per_parent,qty_per_top,manual_flag FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND node_source_type='TECH_RAW' ORDER BY line_no", sourceId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("qty_per_top").toString()).startsWith("0.048");
    assertThat(rows.get(1).get("qty_per_top").toString()).startsWith("0.036");
    assertThat(rows.get(0).get("path")).isNotEqualTo(rows.get(1).get("path"));
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", String.class, productId)).isEqualTo("PENDING");
    assertThat(validation.validate(taskId, 101L, WANG).issues()).anySatisfy(issue -> assertThat(issue.moduleType()).isEqualTo("PRICE"));
    completePrice(WANG);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    assertThat(service.save(productId, request(service.read(productId, null, WANG), true), WANG).bomComposed()).isTrue();
  }

  @Test void missingDrawingNetWeightUsesMatchedU9WeightForDisplaySavingAndHistoricalVersion() {
    confirmC();
    jdbc.update("UPDATE lp_electronic_drawing_source_node SET reference_weight=NULL,reference_weight_unit=NULL WHERE supplement_version_id=? AND resolved_material_code=?", sourceId, key + "-B");
    jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight='10.5' WHERE organization_code='COMMERCIAL' AND material_code=?", key + "-B");
    var current = service.read(productId, null, WANG);
    assertThat(current.source().nodes().stream().filter(row -> (key + "-B").equals(row.parentMaterialNo())))
        .hasSize(2).allSatisfy(row -> {
          assertThat(row.sourceNetWeight()).isEqualByComparingTo("10.5");
          assertThat(row.sourceNetWeightUnit()).isEqualTo("g");
        });
    var saved = service.save(productId, request(current, true), WANG);
    assertThat(saved.saved().items().stream().filter(row -> (key + "-B").equals(row.parentMaterialNo())))
        .hasSize(2).allSatisfy(row -> {
          var input = TechnicalDataManufacturingRules.calculationInput(row);
          assertThat(input.netWeightG()).isEqualByComparingTo("10.5");
          assertThat(input.grossWeightG()).isEqualByComparingTo("12");
          assertThat(row.netWeightKg()).isEqualByComparingTo("0.0105");
        });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND resolved_material_code=? AND reference_weight IS NOT NULL", Long.class, sourceId, key + "-B")).isZero();
    completePrice(WANG);
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM", "MANUFACTURING", "PRICE"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, repository.findProduct(productId).orElseThrow().getRowVersion(), 101L);
    jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight='99' WHERE organization_code='COMMERCIAL' AND material_code=?", key + "-B");
    var historical = service.read(productId, frozen.getId(), WANG);
    assertThat(historical.saved()).isEqualTo(saved.saved());
    assertThat(historical.historical()).isTrue();
  }

  @Test void deletingDraftRawClearsCompositionAndCannotSubmitAnEmptyGapWhileFrozenSnapshotSurvivesReturn() {
    confirmC(); var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    completePrice(WANG);
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM", "MANUFACTURING", "PRICE"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, repository.findProduct(productId).orElseThrow().getRowVersion(), 101L);
    String fingerprint = frozen.getContentFingerprint();
    assertThat(service.read(productId, null, WANG).editable()).isFalse();
    versions.restore(repository.lockProduct(productId).orElseThrow(), person, frozen.getId(), 101L);
    var removed = service.save(productId, request(service.read(productId, null, WANG), false), WANG);
    assertThat(removed.saved().items()).isEmpty(); assertThat(removed.issues()).isNotEmpty(); assertThat(removed.bomComposed()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?", Long.class, sourceId)).isZero();
    assertThat(jdbc.queryForObject("SELECT composition_fingerprint FROM lp_quote_bom_supplement_version WHERE id=?", String.class, sourceId)).isNull();
    assertThat(repository.findVersion(frozen.getId()).orElseThrow().getContentFingerprint()).isEqualTo(fingerprint);
    assertThat(codec.manufacturing(repository.findVersion(frozen.getId()).orElseThrow())).isEqualTo(saved.saved());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
  }

  @Test void returnedUnchangedManufacturingDoesNotRebuildTheDrawingAndKeepsItsOriginalMaterialEvidence() {
    confirmC();
    var input = request(service.read(productId, null, WANG), true);
    var saved = service.save(productId, input, WANG);
    completePrice(WANG);
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM", "MANUFACTURING", "PRICE"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, repository.findProduct(productId).orElseThrow().getRowVersion(), 101L);
    versions.restore(repository.lockProduct(productId).orElseThrow(), person, frozen.getId(), 101L);
    jdbc.update("UPDATE lp_material_master_raw SET import_batch_id=? WHERE material_code=?", key + "-NEXT", key + "-RAW");
    var composition = jdbc.queryForObject("SELECT composition_fingerprint FROM lp_quote_bom_supplement_version WHERE id=?", String.class, sourceId);
    input.setExpectedVersion(repository.findProduct(productId).orElseThrow().getRowVersion());
    var unchanged = service.save(productId, input, WANG);
    assertThat(unchanged.saved()).isEqualTo(saved.saved());
    assertThat(unchanged.issues()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT composition_fingerprint FROM lp_quote_bom_supplement_version WHERE id=?", String.class, sourceId)).isEqualTo(composition);
    assertThat(validation.validate(taskId, 101L, WANG).issues()).isEmpty();
    versions.freeze(repository.lockProduct(productId).orElseThrow(), person, unchanged.expectedVersion(), 101L);
    assertThat(codec.manufacturing(repository.findVersion(frozen.getId()).orElseThrow())).isEqualTo(saved.saved());
  }

  @Test void authorizationOriginSpoofingAndDuplicateParentAreRejectedAndAdminCannotWrite() {
    var state = service.read(productId, null, WANG);
    assertThat(service.read(productId, null, LI).editable()).isFalse();
    assertThatThrownBy(() -> service.save(productId, request(state, true), LI)).hasMessageContaining("未分派给本人");
    var duplicate = request(state, true); duplicate.setItems(List.of(duplicate.getItems().getFirst(), duplicate.getItems().getFirst()));
    assertThatThrownBy(() -> service.save(productId, duplicate, WANG)).hasMessageContaining("只能补一种原材料");
    var forged = request(state, true); forged.getItems().getFirst().unknown("netWeightKg", "0.001");
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("来源字段");
    assertThatThrownBy(() -> service.save(productId, request(state, true), ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, request(state, true), WANG);
    assertThat(repository.findVersion(saved.versionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM lp_quote_tech_module WHERE product_id=? AND module_type='MANUFACTURING'", Long.class, productId)).isEqualTo(101L);
  }

  @Test void quoteViewerCanReadUnassignedManufacturingRowsWithoutCreatingTasksOrVersions() {
    jdbc.update("UPDATE lp_quote_tech_product SET active_flag=0,active_lock_key=NULL WHERE id=?", productId);
    jdbc.update("UPDATE lp_quote_tech_task SET active_flag=0,active_lock_key=NULL WHERE id=?", taskId);
    var before = jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM lp_quote_tech_task) tasks, (SELECT COUNT(*) FROM lp_quote_tech_data_version) versions, (SELECT COUNT(*) FROM lp_quote_bom_preparation_record) preparations");
    for (int attempt = 0; attempt < 2; attempt++) {
      var viewed = service.readQuote(itemId, "2026-09", ADMIN);
      assertThat(viewed.productId()).isNull(); assertThat(viewed.saved()).isNull();
      assertThat(viewed.editable()).isFalse(); assertThat(viewed.historical()).isFalse();
      assertThat(viewed.source().sourceVersionId()).isEqualTo(sourceId);
      assertThat(viewed.source().nodes()).extracting(node -> node.state().name())
          .containsExactly("U9_READY", "MISSING_RAW", "MISSING_RAW", "WAIT_FINANCE");
      assertThat(viewed.source().nodes().get(1).parentMaterialNo()).isEqualTo(key + "-B");
    }
    assertThat(service.readQuote(itemId, "2026-10", ADMIN).source().sourceVersionId()).isNull();
    assertThat(jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM lp_quote_tech_task) tasks, (SELECT COUNT(*) FROM lp_quote_tech_data_version) versions, (SELECT COUNT(*) FROM lp_quote_bom_preparation_record) preparations")).isEqualTo(before);
  }

  @Test void quoteViewerReadsSubmittedManufacturingDetailsButNeverTheTechnicianDraft() {
    confirmC();
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    completePrice(WANG);
    assertThat(service.readQuote(itemId, "2026-09", ADMIN).saved()).isNull();
    assertThat(moduleMapper.selectByTaskId(taskId).stream().filter(row -> Set.of("DRAWING_BOM", "MANUFACTURING", "PRICE").contains(row.getModuleType())))
        .allSatisfy(row -> assertThat(row.getModuleStatus()).as(row.getModuleType() + ": " + row.getLastValidationMessage()).isEqualTo("READY"));
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM", "MANUFACTURING", "PRICE"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, repository.findProduct(productId).orElseThrow().getRowVersion(), 101L);
    var snapshot = codec.versionContentJson(frozen, codec.readReferenceSnapshot(frozen.getReferenceSnapshotJson()), List.of(), List.of(), List.of());
    jdbc.update("""
        INSERT INTO lp_quote_tech_submission(task_id,product_id,technical_version_id,submission_round,request_id,
          expected_task_version,expected_product_version,content_schema_version,content_fingerprint,content_snapshot_json,
          summary_json,assignee_user_id,submitted_by,prepared_at,submission_status,module_types_json)
        VALUES(?,?,?,1,?,0,0,2,?,?,'[]',101,101,NOW(),'PREPARED','["DRAWING_BOM","MANUFACTURING"]')
        """,taskId,productId,frozen.getId(),key,frozen.getContentFingerprint(),snapshot);
    jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='SENDING' WHERE technical_version_id=?",frozen.getId());
    jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='SENT',sent_at=NOW() WHERE technical_version_id=?",frozen.getId());
    jdbc.update("UPDATE lp_quote_tech_data_version SET version_status='SUBMITTED' WHERE id=?",frozen.getId());
    var viewed = service.readQuote(itemId, "2026-09", ADMIN);
    assertThat(viewed.versionId()).isEqualTo(frozen.getId());
    assertThat(viewed.editable()).isFalse(); assertThat(viewed.historical()).isTrue();
    assertThat(viewed.saved()).isEqualTo(saved.saved());
    assertThat(viewed.saved().items()).allSatisfy(row -> {
      assertThat(row.rawMaterialNo()).isEqualTo(key + "-RAW");
      assertThat(row.netLengthMm()).isEqualByComparingTo("120");
      assertThat(row.grossWeightKg()).isEqualByComparingTo("0.012");
    });
    var deniedSave = request(saved, true); deniedSave.setExpectedVersion(viewed.expectedVersion());
    assertThatThrownBy(() -> service.save(productId, deniedSave, ADMIN)).hasMessageContaining("未分派");
  }

  @Test void quoteManufacturingReadKeepsActorBusinessUnitAndMonthBoundaries() {
    assertThatThrownBy(() -> service.readQuote(itemId, "2026-09", WANG)).hasMessageContaining("无权");
    assertThatThrownBy(() -> service.readQuote(itemId, "2026-09", new TechnicalDataActor(1L,"OA",Set.of("*:*:*"),1L))).hasMessageContaining("无权");
    assertThatThrownBy(() -> service.readQuote(itemId, "invalid", ADMIN)).hasMessageContaining("YYYY-MM");
    var viewer = new TechnicalDataActor(2L,"报价员",Set.of("ingest:quote:cost-run:execute"));
    assertThatThrownBy(() -> service.readQuote(itemId, "2026-09", viewer)).hasMessageContaining("业务单元");
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("viewer","unused",List.of());
    auth.setDetails(java.util.Map.of("businessUnitType","HOUSEHOLD"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try { assertThatThrownBy(() -> service.readQuote(itemId, "2026-09", viewer)).hasMessageContaining("无权访问"); }
    finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
  }

  @Test void fulfilledFormalRequirementStopsConsumingRetainedTechnicalDraft() {
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    var module = moduleMapper.selectByTaskId(taskId).stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElseThrow();
    module.setRequiredFlag(0); module.setSourceAvailability("AVAILABLE"); module.setModuleStatus("NOT_REQUIRED");
    moduleMapper.updateById(module);
    assertThat(manufacturingBom.load(contexts.load(itemId, "COMMERCIAL", "210", "2026-09"))).isEmpty();
    var viewed = service.read(productId, null, WANG);
    assertThat(viewed.editable()).isFalse(); assertThat(viewed.issues()).isEmpty();
    assertThat(viewed.saved()).isEqualTo(saved.saved());
  }

  @Test void monthlyManufacturingPreviewKeepsGramPurchasesAndKilogramScrapInTheirActualUnits() {
    String scrap = key + "-SCRAP";
    jdbc.update("UPDATE lp_material_master_raw SET unit='克' WHERE material_code=? AND organization_code='COMMERCIAL'", key + "-RAW");
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,'gram purchase','克',0.066,0,'MPP12-S','2026-09-01','2027-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",key+"-RAW");
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) "
        + "VALUES('COMMERCIAL','2026-09',?,'kg scrap','FIXED',5,'{}','IMPORT','2026-09',?,'MPP12',NOW(),'MPP12',NOW())",scrap,"e".repeat(64));
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type,created_at,updated_at) VALUES(?,?,'克',1,'COMMERCIAL',NOW(),NOW())",key+"-RAW",scrap);
    confirmC();
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    assertThat(saved.issues()).isEmpty();
    var source = jdbc.queryForMap("SELECT path,qty_per_top FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND material_code=? ORDER BY line_no LIMIT 1",sourceId,key+"-B");
    var date = LocalDateTime.of(2026,9,24,12,0);
    var parent = new BomCostingRow(); parent.setOaNo(key); parent.setOaFormItemId(itemId); parent.setTopProductCode(key);
    parent.setMaterialCode(key+"-B"); parent.setMaterialName("technical parent"); parent.setUnit("只"); parent.setPath(source.get("path").toString());
    parent.setQtyPerTop((BigDecimal)source.get("qty_per_top")); parent.setQtyPerParent(parent.getQtyPerTop()); parent.setPriceOrgCode("210");
    parent.setMaterialOrganizationCode("COMMERCIAL"); parent.setBusinessUnitType("COMMERCIAL"); parent.setPeriodMonth("2026-09");
    parent.setBuildBatchId(key); parent.setBuiltAt(date); parent.setAsOfDate(date.toLocalDate()); parent.setBomPurpose("主制造");
    parent.setIsCostingRow(1); parent.setRawVersionEffectiveFrom(date.toLocalDate()); parent.setSubtreeCostRequired(1);
    parent.setSettlementRowType("MAKE_PARENT"); parent.setShapeAttr("制造件"); parent.setLevel(1); costingRows.insert(parent);
    var plan = new com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem(); plan.setBomRow(parent);
    plan.setMaterialCode(parent.getMaterialCode()); plan.setItemType("MAKE"); plan.setStatus("READY");
    var scenario = com.sanhua.marketingcost.service.PricePrepareScenarioContext.oaLocked(itemId,"210");
    var calculated = monthlyManufacturing.calculate(key,"COMMERCIAL","2026-09",date,scenario,plan);
    assertThat(calculated.getStatus()).as(calculated.getMessage()).isEqualTo("READY");
    assertThat(calculated.getUnitPrice()).isEqualByComparingTo("0.782");
    assertThat(calculated.getAmount()).isEqualByComparingTo("3.128");
    var uses = scenario.monthlyPrices().get().plan().uses();
    assertThat(uses).hasSize(2);
    var rawUse = uses.stream().filter(use -> use.request().family().materialCode().equals(key+"-RAW")).findFirst().orElseThrow();
    var scrapUse = uses.stream().filter(use -> use.request().family().materialCode().equals(scrap)).findFirst().orElseThrow();
    assertThat(rawUse.request().family().unit()).isEqualTo("克");
    assertThat(rawUse.cumulativeQuantity()).isEqualByComparingTo("48");
    assertThat(scrapUse.request().family().unit()).isEqualTo("KG");
    assertThat(scrapUse.cumulativeQuantity()).isEqualByComparingTo("0.008");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?)",Integer.class,key+"-RAW",scrap)).isZero();
  }

  private void confirmC() {
    material(key + "-C", key + "-DC", "采购件", "只");
    Long node = jdbc.queryForObject("SELECT id FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND source_sequence='4'", Long.class, sourceId);
    assertThat(sourceNodes.updateResolution(node, "UNMATCHED", "MANUALLY_SELECTED", key + "-C", "FINANCE", LocalDateTime.now())).isTrue();
  }

  private TechnicalDataManufacturingSaveRequest request(TechnicalDataManufacturingResponse state, boolean fill) {
    var request = new TechnicalDataManufacturingSaveRequest(); request.setExpectedVersion(state.expectedVersion());
    request.setSourceVersionId(state.source().sourceVersionId()); request.setSourceFingerprint(state.source().fingerprint());
    request.setItems(fill ? state.source().nodes().stream().filter(row -> row.state() == TechnicalDataManufacturingSourceQuery.State.MISSING_RAW).map(row -> {
      var item = new TechnicalDataManufacturingSaveRequest.Item(); item.setParentSourceNodeId(row.sourceNodeId()); item.setRawMaterialNo(key + "-RAW");
      item.setNetLengthMm(new BigDecimal("120")); item.setGrossWeightKg(new BigDecimal("0.012")); return item;
    }).toList() : List.of());
    return request;
  }

  private void material(String code, String drawing, String shape, String unit) {
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,drawing_no,material_name,shape_attr,unit,organization_code,import_batch_id,source_type,active_flag,main_category_code) VALUES(?,?,?,?,?,'COMMERCIAL',?,'EXCEL',1,'111001008')", code, drawing, code, shape, unit, key);
  }

  private byte[] historicalWorkbook() throws Exception {
    byte[] bytes;
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("明细");
      var headers = List.of("序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "单重", "备注");
      var header = sheet.createRow(0); for (int col = 0; col < headers.size(); col++) header.createCell(col).setCellValue(headers.get(col));
      for (int row = 1; row <= 4; row++) {
        var values = List.of(String.valueOf(row), key + (row == 1 ? "-DA" : row == 4 ? "-DC" : "-DB"), "接管" + row, "铜", "B", "B", row == 2 ? "4" : "3", "10", "");
        var target = sheet.createRow(row); for (int col = 0; col < values.size(); col++) target.createCell(col).setCellValue(values.get(col));
      }
      workbook.write(out); bytes = out.toByteArray();
    }
    return bytes;
  }

  /** 已存的旧 g 单位来源仍须可核算；不再通过已经退役的 Excel 获取/解析接口造来源。 */
  private Long attachHistoricalDrawing() throws Exception {
    var context = contexts.load(itemId, "COMMERCIAL", "210", "2026-09");
    byte[] workbook = historicalWorkbook();
    var source = new QuoteBomSupplementVersion();
    source.setPreparationId(context.preparationId()); source.setTaskNo(context.taskNo()); source.setOaNo(key);
    source.setOaFormItemId(itemId); source.setQuoteProductCode(key); source.setProductType("NON_BARE");
    source.setSupplementScope("NON_BARE_FULL_BOM"); source.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    source.setElectronicDrawingNo(key); source.setSourceFileName("historical-drawing.xlsx");
    source.setSourceFileSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workbook)));
    source.setSourceFileSize((long) workbook.length); source.setSourceSheetName("明细");
    source.setSourceRequestId(key); source.setSourceAcquiredAt(LocalDateTime.of(2026,9,1,0,0));
    source.setMaterialOrgCode("COMMERCIAL"); source.setVersionNo(1); source.setVersionStatus("DRAFT"); source.setActiveFlag(1);
    source.setPeriodMonth("2026-09"); source.setEffectiveFrom(java.time.LocalDate.of(2026,9,1));
    drawingVersions.insert(source);
    var rows = new java.util.ArrayList<ElectronicDrawingSourceNode>();
    for (int row = 1; row <= 4; row++) {
      var node = new ElectronicDrawingSourceNode(); node.setSupplementVersionId(source.getId());
      node.setSourceRowNo(row+1); node.setSourceSequence(Integer.toString(row));
      node.setDrawingCode(key + (row == 1 ? "-DA" : row == 4 ? "-DC" : "-DB"));
      node.setSourceName("接管"+row); node.setMaterial("铜"); node.setImportanceClass("B"); node.setHsfRiskClass("B");
      node.setQty(new BigDecimal(row == 2 ? "4" : "3")); node.setReferenceWeight(BigDecimal.TEN);
      node.setReferenceWeightUnit("g"); node.setSourceRemark(""); node.setMatchStatus("UNMATCHED"); rows.add(node);
    }
    sourceNodes.insertAll(source.getId(), rows);
    context = contexts.attachSourceVersion(context, source.getId(), LocalDateTime.now());
    resolution.autoMatch(itemId,"COMMERCIAL","210","2026-09");
    context = contexts.load(itemId,"COMMERCIAL","210","2026-09");
    context = contexts.updateStage(context,"E_DRAWING_MAPPING_PENDING",1L,"报价员",LocalDateTime.now());
    var drawing = TechnicalDataDrawingSnapshot.from(context,source,sourceNodes.findByVersionId(source.getId()));
    var draft = new QuoteTechDataVersion(); draft.setProductId(productId); draft.setVersionNo(1);
    draft.setVersionStatus("DRAFT"); draft.setContentSchemaVersion(2); draft.setProductModel("OA-MODEL");
    draft.setPackageTotalAmount(BigDecimal.ZERO); draft.setAuxiliaryTotalAmount(BigDecimal.ZERO); draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setDrawingBomJson(codec.drawingBomJson(drawing)); draft.setRowVersion(0); draft.setCreatedBy(101L); draft.setUpdatedBy(101L);
    repository.insertVersion(draft);
    var module = moduleMapper.selectByTaskId(taskId).stream().filter(row -> "DRAWING_BOM".equals(row.getModuleType())).findFirst().orElseThrow();
    module.setCurrentVersionId(draft.getId()); module.setModuleStatus("READY"); module.setReferenceSourceType("ELECTRONIC_DRAWING_EXCEL");
    module.setReferenceSourceId(source.getId().toString()); module.setReferenceFingerprint(source.getSourceFileSha256());
    module.setLastValidationCode("DRAWING_SOURCE_VERIFIED");
    assertThat(repository.updateModule(module,module.getRowVersion(),LocalDateTime.now())).isEqualTo(1);
    var product = repository.lockProduct(productId).orElseThrow(); product.setCurrentEditVersionId(draft.getId()); product.setProductStatus("EDITING");
    assertThat(repository.updateProductPointers(product,product.getRowVersion(),LocalDateTime.now())).isEqualTo(1);
    repository.markTaskInProgress(taskId,101L,LocalDateTime.now());
    return source.getId();
  }
}
