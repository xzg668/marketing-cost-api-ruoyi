package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuotationMonthlyPriceInputs;
import com.sanhua.marketingcost.service.impl.CostInputRevisionServiceImpl;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Tag("integration")
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false"})
class MonthlyQuotationInputRevisionIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired PricePrepareService preparations;
  @Autowired BomCostingRowMapper bomRows;
  @Autowired OaFormMapper forms;
  @Autowired OaFormItemMapper items;
  @Autowired QuotationMonthlyPriceInputs monthlyInputs;
  @Autowired QuoteTechDataVersionMapper technicalVersions;
  @Autowired QuoteTechModuleMapper technicalModules;
  @Autowired QuoteTechTaskMapper technicalTasks;
  @Autowired QuoteTechProductMapper technicalProducts;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataPricePublication technicalPublication;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService technicalPersistence;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaDispatchService technicalDispatch;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceApplicationService technicalPrices;
  @Autowired com.sanhua.marketingcost.integration.oa.OaIntegrationProperties oaProperties;
  @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient oa;
  @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.service.SysUserService users;
  @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryRepository directory;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec technicalContent;
  @Autowired com.sanhua.marketingcost.integration.oa.OaMessageCodec messages;
  @Autowired QuoteEffectiveBomNodeMapper effectiveNodes;
  @Autowired QuoteBomPreparationRecordMapper preparationRecords;
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;
  @Autowired com.sanhua.marketingcost.service.QuoteCostingWorkspaceService workspaces;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway monthlyU9;

  @BeforeEach void tenant() {
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("MPP12-REVISION", "", List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
  }
  @AfterEach void clearTenant() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  @Test void dispatchedMissingPriceCanBeEnteredAndReopenedWithoutAdoptingItsDraft() {
    String key = "MPP12-PENDING-" + UUID.randomUUID().toString().substring(0, 8), material = key + "-A";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    jdbc.update("INSERT INTO oa_form(oa_no,process_code,business_unit_type,copper_price) VALUES(?,'FI-SC-005','COMMERCIAL',80000)", key);
    long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", formId, key);
    long itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    buildBom(key, material, formId, itemId, month);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('HZ_KG_OA_TST','TEST',?,?,1)", key, formId);
    for (long id : List.of(1L, 101L)) {
      var user = new com.sanhua.marketingcost.entity.SysUser();
      user.setUserId(id); user.setUserName("mpp-price-" + id); user.setNickName("技术员" + id);
      user.setStatus("0"); user.setDelFlag("0"); user.setEmployeeNo("MPP-" + id);
      when(users.getById(id)).thenReturn(user); when(users.findIdentityById(id)).thenReturn(user);
      when(users.findPermissionsByUserId(id)).thenReturn(Set.of("technical:data:task:edit"));
      when(directory.findActiveBySystemUserId(id)).thenReturn(
          new com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryIdentity(id, user.getEmployeeNo()));
    }
    oaProperties.getOutbound().setFrontendBaseUrl("http://localhost:5173");
    when(oa.submit(any())).thenAnswer(call -> {
      com.fasterxml.jackson.databind.node.ObjectNode body = call.getArgument(0);
      return new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult("test",
          com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.SUCCESS, 200, "0", "成功", body.path("requestId").asText(), null, 1);
    });
    var task = new com.sanhua.marketingcost.entity.QuoteTechTask();
    task.setTaskNo(key); task.setOaFormId(formId); task.setOaFormItemId(itemId); task.setOaNo(key);
    task.setAccountingMonth(month); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("技术员101");
    task = technicalPersistence.createTask(task);
    var product = new com.sanhua.marketingcost.entity.QuoteTechProduct();
    product.setTaskId(task.getId()); product.setOaFormItemId(itemId); product.setMaterialNo(key); product.setQuoteNo(key);
    product.setAccountingMonth(month); product.setContentSchemaVersion(2); product.setSourceSnapshotJson("{}"); product.setSourceFingerprint("b".repeat(64));
    product = technicalPersistence.createProduct(product);
    for (String type : com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleType.orderedCodes()) {
      boolean required = "PRICE".equals(type);
      var module = new com.sanhua.marketingcost.entity.QuoteTechModule();
      module.setProductId(product.getId()); module.setModuleType(type); module.setRequiredFlag(required ? 1 : 0);
      module.setRequirementReasonCode(required ? "PRICE_SOURCE_MISSING" : "AVAILABLE"); module.setRequirementReason("MPP12 真实缺价分派");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceCheckedAt(java.time.LocalDateTime.now());
      technicalPersistence.createModule(module);
    }
    long taskId = task.getId(), productId = product.getId();
    var operator = new com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor(1L, "报价员", Set.of("*:*:*"));
    var technician = new com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor(101L, "技术员101", Set.of("technical:data:task:edit"));
    String batch = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status ->
        technicalDispatch.prepare(List.of(technicalTasks.selectByIdForUpdate(taskId)), 101L, Map.of(), UUID.randomUUID().toString(), "c".repeat(64), operator));
    assertThat(technicalDispatch.deliver(batch).status()).isEqualTo("SUCCESS");
    var opened = technicalPrices.get(productId, null, technician);
    assertThat(opened.editable()).isTrue(); assertThat(opened.historical()).isFalse();
    assertThat(opened.requirements().issues()).isEmpty();
    assertThat(opened.requirements().items()).singleElement().satisfies(row -> {
      assertThat(row.materialNo()).isEqualTo(material); assertThat(row.status()).isEqualTo("MISSING"); assertThat(row.unitPrice()).isNull();
    });
    assertThat(opened.owners()).singleElement().satisfies(owner -> assertThat(owner.productId()).isEqualTo(productId));
    var input = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest();
    input.setExpectedVersion(opened.expectedVersion()); input.setRequirementsFingerprint(opened.requirements().fingerprint());
    var price = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest.Item();
    price.setItemKey(opened.requirements().items().getFirst().itemKey()); price.setEntryMode("FIXED"); price.setUnitPrice(new BigDecimal("7"));
    input.setItems(List.of(price));
    var saved = technicalPrices.save(productId, input, technician);
    assertThat(saved.moduleStatus()).isEqualTo("READY"); assertThat(saved.issues()).isEmpty();
    var reopened = technicalPrices.get(productId, null, technician);
    assertThat(reopened.editable()).isTrue(); assertThat(reopened.requirements().items().getFirst().status()).isEqualTo("MISSING");
    assertThat(reopened.content().items().getFirst().unitPrice()).isEqualByComparingTo("7");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE material_code=?", Integer.class, material)).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void formalPreparationPinsPriceInputsAcrossSourceAndPriorityChangesButNotProductChanges(boolean technicalSource) {
    String key = "MPP12-REV-" + UUID.randomUUID().toString().substring(0, 8);
    String material = key + "-A";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", formId, key);
    long itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    buildBom(key, material, formId, itemId, month);
    var form = forms.selectById(formId); var item = items.selectById(itemId);
    var revisions = new CostInputRevisionServiceImpl(jdbc, monthlyInputs);
    String missing = revisions.currentRevision(form, item, month);
    TechnicalFixture technical = technicalSource ? technicalFixed(material, month) : null;
    if (!technicalSource) jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,supplier_name,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,created_at,updated_at) "
        + "VALUES('210',?,'MPP12 revision material','件',11.3,0,'MPP12-S','MPP12 supplier',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','MPP12-fixture',NOW(),NOW())", material, date.toLocalDate().withDayOfMonth(1));
    String available = revisions.currentRevision(form, item, month);
    assertThat(available).isNotEqualTo(missing);
    if (technical != null) {
      jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=NULL WHERE id=?", technical.product());
      assertThat(revisions.currentRevision(form, item, month)).isNotEqualTo(available);
      jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=? WHERE id=?", technical.effective(), technical.product());
    }
    var request = new PricePrepareGenerateRequest(); request.setOaNo(key); request.setOaFormItemId(itemId); request.setTopProductCode(key);
    request.setPeriodMonth(month); request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    assertThat(preparations.generate(request).getStatus()).isEqualTo("SUCCESS");
    var monthly = monthlyInputs.read(form, item, month);
    assertThat(monthly.preparationKnown()).isTrue(); assertThat(monthly.unadoptedMaterials()).isEmpty();
    String adopted = revisions.currentRevision(form, item, month);
    String savedEvidence = jdbc.queryForObject("SELECT source_evidence_json FROM lp_material_monthly_price WHERE material_code=?", String.class, material);
    if (technical != null) {
      assertThat(savedEvidence).contains("TECH_FIXED");
      // A genuinely new final source version and a later returned source must not replace the adopted fact.
      returnTechnicalSource(technical, technical.personal());
      assertThat(revisions.currentRevision(form, item, month)).isEqualTo(adopted);
      long later = technical.effective() + 100;
      for (int index = 0; index < 2; index++) {
        jdbc.update("INSERT INTO lp_quote_tech_data_version(id,product_id,version_no,version_status,content_schema_version,price_items_json,created_from_version_id) VALUES(?,?,?,'DRAFT',2,?,?)",
            later + index, technical.product(), 3 + index, pricesJson(material, "99"),
            index == 0 ? technical.personal() : technical.effective());
        if (index == 0) setTechnicalModule(technical.priceModule(), later, "READY");
        seal(later + index, technical.product());
        if (index == 0) setTechnicalModule(technical.priceModule(), later, "SUBMITTED");
      }
      assertThat(jdbc.update("UPDATE lp_quote_tech_product SET latest_submitted_version_id=?,effective_version_id=?,product_status='SUBMITTED' WHERE id=?",
          later, later + 1, technical.product())).isEqualTo(1);
      assertThat(jdbc.update("UPDATE lp_quote_tech_task SET task_status='SUBMITTED',review_status='PENDING',task_version=task_version+1 WHERE id=? AND task_status='PARTIALLY_RETURNED'",
          technical.task())).isEqualTo(1);
      new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
          technicalPublication.publish(technicalTasks.selectById(technical.task()),
              technicalProducts.selectById(technical.product()), technicalVersions.selectById(later)));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE material_code=? AND technical_version_id=? AND fixed_price=7",
          Integer.class, material, technical.personal())).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE material_code=? AND technical_version_id=? AND fixed_price=99",
          Integer.class, material, later)).isEqualTo(1);
      assertThat(revisions.currentRevision(form, item, month)).isEqualTo(adopted);
      returnTechnicalSource(technical, later);
      assertThat(revisions.currentRevision(form, item, month)).isEqualTo(adopted);
    }
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=99 WHERE material_code=?", material);
    jdbc.update("UPDATE lp_material_price_type SET manual_priority=9,priority_updated_at=NOW() WHERE material_code=?", material);
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(material_code,material_name,supplier_code,supplier_name,supply_ratio,business_unit_type,created_by,created_at,updated_by,updated_at) VALUES(?,'revision material','MPP12-NEW','new supplier',1,'COMMERCIAL','MPP12',NOW(),'MPP12',NOW())", material);
    assertThat(revisions.currentRevision(form, item, month)).isEqualTo(adopted);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", material);
    assertThat(revisions.currentRevision(form, item, month)).isEqualTo(adopted);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT source_evidence_json FROM lp_material_monthly_price WHERE material_code=?", String.class, material)).isEqualTo(savedEvidence);
    item.setPackageQty(new BigDecimal("8"));
    assertThat(revisions.currentRevision(form, item, month)).isNotEqualTo(adopted);
  }

  record TechnicalFixture(long task, long product, long priceModule, long personal, long effective) {}

  private void returnTechnicalSource(TechnicalFixture source, long personalVersion) {
    // Match confirmed OA return ordering: reopen the aggregate task before restoring module editability.
    // The database intentionally rejects changing module contents while the task is still SUBMITTED.
    assertThat(jdbc.update("UPDATE lp_quote_tech_task SET task_status='PARTIALLY_RETURNED',review_status='NOT_STARTED',task_version=task_version+1 WHERE id=? AND task_status='SUBMITTED'",
        source.task())).isEqualTo(1);
    assertThat(jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=NULL,effective_review_round=NULL,effective_at=NULL,product_status='RETURNED' WHERE id=?",
        source.product())).isEqualTo(1);
    setTechnicalModule(source.priceModule(), personalVersion, "RETURNED");
    var version = technicalVersions.selectById(personalVersion);
    assertThat(technicalVersions.transitionStatus(personalVersion, "SUBMITTED", "RETURNED", version.getRowVersion(),
        version.getContentFingerprint(), version.getReferenceSnapshotJson(), 1L, java.time.LocalDateTime.now())).isEqualTo(1);
    assertThat(technicalVersions.selectById(personalVersion).getVersionStatus()).isEqualTo("RETURNED");
  }

  private void setTechnicalModule(long moduleId, long versionId, String status) {
    var module = technicalModules.selectById(moduleId);
    module.setCurrentVersionId(versionId); module.setModuleStatus(status);
    assertThat(technicalModules.updateWithVersion(module, module.getRowVersion(), java.time.LocalDateTime.now())).isEqualTo(1);
  }

  private void buildBom(String key, String material, long form, long item, String month) {
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    var sources = new ArrayList<com.sanhua.marketingcost.entity.BomRawHierarchy>();
    for (int level = 0; level < 2; level++) {
      String code = level == 0 ? key : material;
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,?,?)",
          code, code, key, level == 0 ? "11" : "15", level == 0 ? "制造件" : "采购件");
      var raw = new com.sanhua.marketingcost.entity.BomRawHierarchy();
      raw.setTopProductCode(key); raw.setParentCode(key); raw.setMaterialCode(code); raw.setMaterialName(code);
      raw.setPath("/" + key + "/" + (level == 0 ? "" : material + "/")); raw.setLevel(level); raw.setSortSeq(level);
      raw.setQtyPerParent(level == 0 ? BigDecimal.ONE : new BigDecimal("2.5")); raw.setQtyPerTop(raw.getQtyPerParent());
      raw.setShapeAttr(level == 0 ? "制造件" : "采购件"); raw.setBomPurpose("主制造"); raw.setBomVersion("MPP12");
      raw.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); raw.setBomStatus("已核准"); raw.setIsLeaf(level);
      raw.setU9IsCostFlag(1); raw.setSourceType("U9"); raw.setSourceImportBatchId(key); raw.setBuildBatchId(key);
      raw.setBuiltAt(date); raw.setPriceOrgCode("210"); raw.setBusinessUnitType("COMMERCIAL"); rawHierarchy.insert(raw);
      sources.add(raw);
    }
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, key, month, "COMMERCIAL", key, key, null, null, "210", "COMMERCIAL", date.toLocalDate(), date));
    assertThat(original.status()).as(original.message())
        .isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
    assertThat(original.monthlySnapshotId()).isPositive();
    String build = key + "-EFFECTIVE";
    for (var raw : sources) {
      var node = new com.sanhua.marketingcost.entity.QuoteEffectiveBomNode();
      node.setOriginMonthlySnapshotId(original.monthlySnapshotId()); node.setEffectiveVariantHash(original.structureFingerprint());
      node.setBuildBatchId(build); node.setTopProductCode(key); node.setCostPeriodMonth(month); node.setPriceOrgCode("210");
      node.setNodeKey(raw.getLevel() == 0 ? "ROOT" : "PART"); node.setParentNodeKey(raw.getLevel() == 0 ? null : "ROOT");
      node.setNodeLevel(raw.getLevel()); node.setSortSeq(raw.getSortSeq()); node.setNodePath(raw.getPath());
      node.setMaterialCode(raw.getMaterialCode()); node.setMaterialName(raw.getMaterialName()); node.setMaterialUnit("件");
      node.setQtyPerParent(raw.getQtyPerParent()); node.setQtyPerTop(raw.getQtyPerTop());
      node.setEffectiveMaterialShape(raw.getLevel() == 0 ? "MANUFACTURE" : "PURCHASE"); node.setSourceMaterialShape(node.getEffectiveMaterialShape());
      node.setShapeResolutionSource("U9"); node.setSourceBomType("U9"); node.setSourceBomBatchId(key);
      node.setSourceNodePath(raw.getPath()); node.setSourceHierarchyId(raw.getId());
      effectiveNodes.insert(node);
    }
    var record = new com.sanhua.marketingcost.entity.QuoteBomPreparationRecord();
    record.setOaFormId(form); record.setOaFormItemId(item); record.setOaNo(key); record.setQuoteProductCode(key);
    record.setPriceOrgCode("210"); record.setMaterialOrganizationCode("COMMERCIAL"); record.setProductType("NON_BARE");
    record.setCostPeriodMonth(month); record.setPreparationStatus("READY"); record.setActiveFlag(1); preparationRecords.insert(record);
    costingBoms.buildFromEffectiveBom(item, build);
    var workspace = workspaces.getOrCreate(key, item, key, month, "COMMERCIAL");
    int lock = workspace.getLockVersion(); workspace.setCurrentBomBuildBatchId(build); workspace.setBomSourceFingerprint(original.structureFingerprint());
    workspaces.update(workspace, lock);
  }

  private String pricesJson(String material, String amount) {
    var price = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem(
        "ITEM:" + material, material, "210", "件", "CNY", "FIXED", new BigDecimal(amount),
        null, null, "MPP12 controlled technical source", null, null, null);
    return technicalContent.pricesJson(new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Prices(List.of(price)));
  }

  private TechnicalFixture technicalFixed(String material, String month) {
    long first = java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000_000, 900_000_000);
    long form = first, originalItem = first + 1, flow = first + 2, task = first + 3,
        product = first + 4, personal = first + 5, effective = first + 6;
    String oa = "MPP12-TECH-SOURCE-" + first;
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, oa);
    jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type) VALUES(?,?,'SOURCE-TOP','COMMERCIAL')", originalItem, form);
    jdbc.update("INSERT INTO lp_oa_technical_flow(id,source_system,environment,oa_form_id,accounting_month,external_document_id,finance_ready,finance_confirmed_fingerprint) VALUES(?,'MOCK','ISOLATED',?,?,?,1,?)",
        flow, form, month, "DOC-" + flow, messages.canonicalHash("[]"));
    jdbc.update("INSERT INTO lp_quote_tech_task(id,task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,business_unit_type,applicable_org_code,task_status,assignee_user_id,assignee_name,active_flag,active_lock_key,oa_flow_id) VALUES(?,?,?,?,?,?,'COMMERCIAL','210','SUBMITTED',1,'fixture',1,?,?)",
        task, "TASK-" + task, form, originalItem, oa, month, "ITEM:" + originalItem + ":MONTH:" + month, flow);
    jdbc.update("INSERT INTO lp_quote_tech_product(id,task_id,oa_form_item_id,quote_no,accounting_month,source_snapshot_json,source_fingerprint,product_status,active_flag,active_lock_key,content_schema_version,material_no) VALUES(?,?,?,?,?,'{}',?,'SUBMITTED',1,?,2,'SOURCE-TOP')",
        product, task, originalItem, oa, month, "a".repeat(64), "ITEM:" + originalItem + ":MONTH:" + month);
    long priceModule = 0, moduleId = first + 10;
    for (String type : com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleType.orderedCodes()) {
      boolean price = type.equals("PRICE"); long id = moduleId++; if (price) priceModule = id;
      jdbc.update("INSERT INTO lp_quote_tech_module(id,product_id,module_type,required_flag,requirement_reason_code,requirement_reason,source_availability,source_checked_at,module_status,current_version_id,assignee_user_id,assignee_name) VALUES(?,?,?,?,?,?,?,NOW(),?,?,?,?)",
          id, product, type, price ? 1 : 0, "ISOLATED", "MPP12 controlled source", price ? "MISSING" : "AVAILABLE",
          price ? "SUBMITTED" : "NOT_REQUIRED", price ? personal : null, price ? 1L : null, price ? "fixture" : null);
    }
    for (long id : List.of(personal, effective)) {
      jdbc.update("INSERT INTO lp_quote_tech_data_version(id,product_id,version_no,version_status,content_schema_version,price_items_json) VALUES(?,?,?,'DRAFT',2,?)",
          id, product, id == personal ? 1 : 2, pricesJson(material, "7"));
      seal(id, product);
    }
    jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=? WHERE id=?", effective, product);
    jdbc.update("INSERT INTO lp_quote_tech_price_claim(material_code,owner_module_id,organization_code,business_unit_type,price_unit,currency) VALUES(?,?,'210','COMMERCIAL','件','CNY')", material, priceModule);
    jdbc.update("INSERT INTO lp_price_fixed_item(material_code,org_code,business_unit_type,unit,fixed_price,tax_included,source_kind,technical_version_id,technical_item_key,technical_publication_status,source_type,created_at,updated_at,pricing_month) VALUES(?,'210','COMMERCIAL','件',7,0,'TECH_SUPPLEMENTAL',?,?,'AVAILABLE','PURCHASE_FIXED',NOW(),NOW(),?)",
        material, personal, "ITEM:" + material, month);
    return new TechnicalFixture(task, product, priceModule, personal, effective);
  }

  private void seal(long id, long product) {
    var version = technicalVersions.selectById(id); var roster = technicalModules.selectByProductId(product);
    version.setReferenceSnapshotJson(technicalContent.referenceSnapshotJson(technicalContent.moduleSnapshots(roster)));
    version.setSourceFactsJson(technicalContent.sourceFactsJson("{}", roster));
    var changedAt = java.time.LocalDateTime.now(); version.setUpdatedBy(1L);
    assertThat(technicalVersions.updateDraftWithVersion(version, version.getRowVersion(), changedAt)).isEqualTo(1);
    // Match production sealing: hash the persisted, database-normalized draft, then freeze it.
    var persisted = technicalVersions.selectById(id);
    String hash = technicalContent.fingerprint(persisted,
        technicalContent.readReferenceSnapshot(persisted.getReferenceSnapshotJson()), List.of(), List.of(), List.of());
    assertThat(technicalVersions.transitionStatus(id, "DRAFT", "FROZEN", persisted.getRowVersion(), hash,
        persisted.getReferenceSnapshotJson(), 1L, changedAt)).isEqualTo(1);
    var frozen = technicalVersions.selectById(id);
    assertThat(frozen.getVersionStatus()).isEqualTo("FROZEN");
    assertThat(technicalVersions.transitionStatus(id, "FROZEN", "SUBMITTED", frozen.getRowVersion(), hash,
        frozen.getReferenceSnapshotJson(), 1L, changedAt)).isEqualTo(1);
    var submitted = technicalVersions.selectById(id);
    assertThat(submitted.getVersionStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.getContentFingerprint()).isEqualTo(hash);
  }
}
