package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.MakePartChildNetWeight;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.mapper.MakePartChildNetWeightMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotBuilder;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartPricePrepareStrategy;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.impl.CostInputRevisionServiceImpl;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataMaterialPriceQuery;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class SettlementConsumerIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired MakePartMaterialPriceResolveService materials;
  @Autowired SettlementPriceImportService imports;
  @Autowired SettlementPriceRepository prices;
  @Autowired SettlementRuleRevisionFactory ruleRevisions;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired PackageComponentPriceService packages;
  @Autowired PackageComponentSnapshotMapper packageSnapshots;
  @Autowired PackageComponentSnapshotDetailMapper packageDetails;
  @Autowired PackageComponentPricePrepareStrategy packagePreparation;
  @Autowired CostRunPartItemService costParts;
  @Autowired BomCostingRowMapper bomRows;
  @Autowired TechnicalDataMaterialPriceQuery technicalPrices;
  @Autowired SettlementPriceReader reader;
  @Autowired SettlementEffectivePriceService effectivePrices;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService finance;
  @Autowired PricePrepareItemMapper preparations;
  @Autowired CostRunPartItemMapper storedParts;
  @Autowired CostRunTraceSnapshotMapper traces;
  @Autowired CostRunTraceSnapshotService traceService;
  @Autowired CostRunTraceSnapshotBuilder traceBuilder;
  @Autowired MakePartPriceGenerationService generation;
  @Autowired BomU9SourceMapper u9;
  @Autowired MaterialMasterRawMapper rawMaterials;
  @Autowired MakePartChildNetWeightMapper netWeights;
  @Autowired MakePartNoScrapConfirmationService noScrap;
  @Autowired NormalMaterialPricePrepareStrategy normalPreparation;
  @Autowired OaFormMapper forms;
  @Autowired OaFormItemMapper formItems;
  @Autowired CurrentU9BomGateway currentBom;
  @Autowired MakePartPricePrepareStrategy makePreparation;
  @Autowired com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec monthlyCodec;
  private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 11, 15, 10, 0);
  private static final Path ARCHIVE;
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-consumer-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }

  @BeforeEach void source() throws Exception {
    jdbc.update("DELETE FROM lp_material_master_raw WHERE import_batch_id LIKE 'STP-%'");
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE source_type='SETTLE_FIXED' AND material_code IN ('809633','203259534','203240646')");
    jdbc.update("DELETE FROM lp_settlement_effective_price");
    jdbc.update("DELETE FROM lp_settlement_price");
    SettlementFinanceTestSupport.clear(jdbc);
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("COMMERCIAL", "2026-10"), "104.526", "24.51");
    jdbc.update("DELETE FROM lp_settlement_base_price");
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { bytes = input.readAllBytes(); }
    var scope = new SettlementScope("COMMERCIAL", "2026-10");
    imports.confirm(bytes, "original.xls", scope, imports.preview(bytes, "original.xls", scope).fingerprint(), "finance");
    var row = prices.effective(scope, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    SettlementRuleTestSupport.legacyWeights(prices, ruleRevisions, row, "7.8", "1.48");
    new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> effectivePrices.generate("COMMERCIAL", "test-fixture"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"210", "220"})
  void manufacturingGenerationPersistsAllModesAndTheirOriginalEvidence(String org) throws Exception {
    String parentCode = "STP-MAKE-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String masterOrg = "210".equals(org) ? "COMMERCIAL" : "PLATE";
    var form = new OaForm();
    form.setOaNo(parentCode);
    form.setBusinessUnitType("COMMERCIAL");
    forms.insert(form);
    var product = new OaFormItem();
    product.setOaFormId(form.getId());
    product.setMaterialNo(parentCode);
    product.setProductName("220".equals(org) ? "板式换热器" : "商用测试产品");
    product.setBusinessUnitType("COMMERCIAL");
    formItems.insert(product);
    var parent = new BomCostingRow();
    parent.setOaNo(parentCode);
    parent.setOaFormItemId(product.getId());
    parent.setTopProductCode(parentCode);
    parent.setMaterialCode(parentCode);
    parent.setLevel(1);
    parent.setPath("/" + parentCode + "/");
    parent.setQtyPerParent(BigDecimal.ONE);
    parent.setQtyPerTop(new BigDecimal("3"));
    parent.setIsCostingRow(1);
    parent.setPriceOrgCode(org);
    parent.setMaterialOrganizationCode(masterOrg);
    parent.setBusinessUnitType("COMMERCIAL");
    parent.setPeriodMonth("2026-11");
    parent.setAsOfDate(AS_OF.toLocalDate());
    parent.setRawVersionEffectiveFrom(AS_OF.toLocalDate().minusMonths(1));
    parent.setBuiltAt(AS_OF);
    parent.setBuildBatchId(parentCode);
    parent.setShapeAttr("制造件");
    bomRows.insert(parent);
    String[] codes = {"809633", "203259534", "203240646"};
    String[] values = {"4.1658", "4.5114", "4.841024707965"};
    String[] modes = {"FIXED_GIVEN", "LINKED_GIVEN", "LINKED_FORMULA"};
    jdbc.update("INSERT INTO lp_bom_raw_hierarchy (price_org_code,top_product_code,parent_code,material_code,level,path,qty_per_top,bom_purpose,is_leaf,effective_from,source_type,build_batch_id,built_at,source_line_key,source_import_batch_id) VALUES (?,?,?,?,0,?,1,'主制造',0,'2026-01-01','U9',?,NOW(),?,?)",
        org, parentCode, parentCode, parentCode, "/" + parentCode + "/", parentCode, parentCode, parentCode);
    for (int i = 0; i < codes.length; i++) {
      jdbc.update("INSERT INTO lp_bom_raw_hierarchy (price_org_code,top_product_code,parent_code,material_code,level,path,qty_per_parent,qty_per_top,shape_attr,bom_purpose,is_leaf,effective_from,source_type,build_batch_id,built_at,source_line_key,source_import_batch_id) VALUES (?,?,?,?,1,?,1,1,'采购件','主制造',1,'2026-01-01','U9',?,NOW(),?,?)",
          org, parentCode, parentCode, codes[i], "/" + parentCode + "/" + codes[i] + "/", parentCode, parentCode + codes[i], parentCode);
      var child = new BomU9Source();
      child.setParentMaterialNo(parentCode);
      child.setChildMaterialNo(codes[i]);
      child.setPriceOrgCode(org);
      child.setBomPurpose("主制造");
      child.setBomVersion("STP-V1");
      child.setChildSeq(i + 1);
      child.setStockUnit("只");
      child.setQtyPerParent(BigDecimal.ONE);
      child.setImportBatchId(parentCode);
      child.setSourceType("EXCEL");
      child.setImportedAt(AS_OF);
      u9.insert(child);
      var master = new MaterialMasterRaw();
      master.setMaterialCode(codes[i]);
      master.setImportBatchId(parentCode);
      master.setOrganizationCode(masterOrg);
      master.setGlobalSeg3TheoreticalNetWeight("10");
      master.setUnit("只");
      master.setActiveFlag(1);
      rawMaterials.insert(master);
      var weight = new MakePartChildNetWeight();
      weight.setMaterialOrganizationCode(masterOrg);
      weight.setParentMaterialNo(parentCode);
      weight.setChildMaterialNo(codes[i]);
      weight.setBomVersion("STP-V1");
      weight.setPeriodMonth("2026-11");
      weight.setNetWeightG(BigDecimal.TEN);
      weight.setSourceType("MANUAL");
      weight.setSourceReference("隔离测试同重毛坯");
      weight.setCreatedAt(AS_OF);
      weight.setUpdatedAt(AS_OF);
      netWeights.insert(weight);
      if (noScrap.findEffective(codes[i], "2026-11", "COMMERCIAL") == null) {
        var confirmation = new NoScrapConfirmRequest();
        confirmation.setBusinessUnitType("COMMERCIAL");
        confirmation.setMaterialNo(codes[i]);
        confirmation.setEffectiveFromMonth("2026-11");
        confirmation.setConfirmReason("隔离测试已确认无废料");
        noScrap.confirm(confirmation, "finance");
      }
    }
    var source = currentBom.read(new QuoteBomReadContext(
        form.getId(), product.getId(), parentCode, "2026-11", "COMMERCIAL", parentCode,
        product.getProductName(), null, null, org, masterOrg, AS_OF.toLocalDate(), AS_OF));
    assertThat(source.status().name()).describedAs(source.message()).isEqualTo("AVAILABLE");
    var scenario = new PricePrepareScenarioContext(QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        "STP-GROUP", "STP-SOURCE", Map.of("Cu", new BigDecimal("999")),
        new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(product.getId()).forOrganization(org));
    for (boolean persist : new boolean[] {false, true}) {
      var rows = generation.calculateForBomRow(parent, AS_OF, scenario, persist);
      assertThat(rows).hasSize(3);
      for (int i = 0; i < codes.length; i++) {
        var row = rows.get(i);
        assertThat(row.getStatus()).describedAs(row.getRemark()).isEqualTo("OK");
        assertThat(row.getRemark()).doesNotContain("ensure");
        assertThat(row.getRawUnitPrice()).isEqualByComparingTo(values[i]);
        assertThat(row.getCostPrice()).isEqualByComparingTo(new BigDecimal(values[i]).setScale(8, java.math.RoundingMode.HALF_UP));
        assertThat(row.getParentTotalCostPrice()).isEqualByComparingTo("13.51822471");
        assertThat(settlementEvidence(row.getRawInputSnapshotJson()).path("mode").asText()).isEqualTo(modes[i]);
        if (persist) assertThat(row.getId()).isPositive();
      }
    }
    var plan = new PricePreparePlanItem();
    plan.setMaterialCode(parentCode);
    plan.setBomRow(parent);
    var prepared = makePreparation.prepare(parentCode, "COMMERCIAL", "2026-11", AS_OF, scenario, plan);
    assertThat(prepared.getStatus()).describedAs(prepared.getMessage()).isEqualTo("READY");
    var traceVersion = storeTraceInput(parentCode, org, "MAKE_PART", prepared.getResultRefType(),
        prepared.getResultRefId(), prepared.getUnitPrice(), prepared.getAmount(), prepared.getInputSnapshotJson());
    var frozen = traceBuilder.build(traceVersion).getFirst();
    assertThat(json.readTree(frozen.getChildrenJson())).hasSize(3);
    // 重算会替换制造件中间结果；底稿必须继续读取报价准备时捕获的完整行。
    jdbc.update("DELETE FROM lp_make_part_price_calc_row WHERE parent_material_no=?", parentCode);
    assertThat(traceBuilder.build(traceVersion).getFirst().getChildrenJson()).isEqualTo(frozen.getChildrenJson());
  }

  @ParameterizedTest
  @CsvSource({"809633,false", "203259534,false", "203240646,true"})
  void draftRevisionTracksOnlyApplicableSettlementInputs(String code, boolean formula) throws Exception {
    long itemId = Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits() % 1000000000L) + 1000000;
    String run = "STP-REV-" + itemId;
    var prep = new PricePrepareItem();
    prep.setPrepareNo(run);
    prep.setOaNo(run);
    prep.setOaFormItemId(itemId);
    prep.setTopProductCode(run);
    prep.setMaterialCode("PACK-" + code);
    prep.setItemType("PACKAGE_COMPONENT");
    prep.setPeriodMonth("2026-11");
    prep.setPriceOrgCode("210");
    prep.setBusinessUnitType("COMMERCIAL");
    prep.setStatus("READY");
    prep.setCurrentFlag(1);
    prep.setInputSnapshotJson("{\"components\":[{\"materialCode\":\"" + code + "\"}]}");
    preparations.insert(prep);
    var form = new OaForm();
    form.setId(itemId);
    form.setOaNo(run);
    form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem();
    item.setId(itemId);
    item.setOaFormId(itemId);
    item.setBusinessUnitType("COMMERCIAL");
    var revisions = new CostInputRevisionServiceImpl(jdbc, (ignoredForm, ignoredItem, ignoredMonth) -> com.sanhua.marketingcost.service.QuotationMonthlyPriceInputs.Scope.unprepared());
    String before = revisions.currentRevision(form, item, "2026-11");
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("COMMERCIAL", "2026-12"), "120", "30");
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("HOUSEHOLD", "2026-11"), "130", "31");
    assertThat(revisions.currentRevision(form, item, "2026-11")).isEqualTo(before);
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("COMMERCIAL", "2026-11"), "110", "26");
    String afterBase = revisions.currentRevision(form, item, "2026-11");
    if (formula) assertThat(afterBase).isNotEqualTo(before);
    else assertThat(afterBase).isEqualTo(before);
    if (!code.equals("809633")) {
      var rule = prices.effective(new SettlementScope("COMMERCIAL", "2026-11"), code, SettlementPriceType.SETTLE_LINKED).getFirst();
      SettlementRuleTestSupport.importWeights(imports, new SettlementScope("COMMERCIAL", rule.getPriceMonth()), code, 8, 2);
      assertThat(revisions.currentRevision(form, item, "2026-11")).isNotEqualTo(afterBase);
    }
    assertThat(json.readTree(preparations.selectById(prep.getId()).getInputSnapshotJson()))
        .isEqualTo(json.readTree(prep.getInputSnapshotJson()));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"210", "220"})
  void frozenTraceUsesCapturedSettlementIdentityAndSurvivesMaintenance(String org) throws Exception {
    String run = "STP-TRACE-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String[] codes = {"809633", "203259534", "203240646"};
    String[] modes = {"FIXED_GIVEN", "LINKED_GIVEN", "LINKED_FORMULA"};
    for (int i = 0; i < codes.length; i++) {
      var result = reader.resolve(new SettlementPriceRequest("COMMERCIAL", codes[i], "2026-11", org,
          i == 0 ? SettlementPriceType.SETTLE_FIXED : SettlementPriceType.SETTLE_LINKED));
      // 故意制造不同来源表的同 ID；底稿不能凭数字 ID 去旧表取价。
      jdbc.update("DELETE FROM lp_price_fixed_item WHERE id=?", result.resultRefId());
      jdbc.update("INSERT INTO lp_price_fixed_item (id,material_code,source_type,fixed_price,business_unit_type,created_at,updated_at) VALUES (?,?,'SETTLE_FIXED',777,'COMMERCIAL',NOW(),NOW())",
          result.resultRefId(), codes[i]);
      var prep = new PricePrepareItem();
      prep.setPrepareNo(run);
      prep.setOaNo(run);
      prep.setOaFormItemId(90001L);
      prep.setTopProductCode(run + "-TOP");
      prep.setMaterialCode(codes[i]);
      prep.setItemType("NORMAL");
      prep.setPeriodMonth("2026-11");
      prep.setPriceOrgCode(org);
      prep.setBusinessUnitType("COMMERCIAL");
      prep.setStatus("READY");
      prep.setUnitPrice(result.unitPrice());
      prep.setQuantity(new BigDecimal("3"));
      prep.setAmount(result.unitPrice().multiply(prep.getQuantity()));
      prep.setPriceSource(result.priceSource());
      prep.setResultRefType(SettlementPriceReader.RESULT_REF_TYPE);
      prep.setResultRefId(result.resultRefId());
      prep.setInputSnapshotJson(result.evidence().inputSnapshotJson());
      preparations.insert(prep);
      var part = new CostRunPartItem();
      part.setOaNo(run);
      part.setCostRunNo(run);
      part.setProductCode(run + "-TOP");
      part.setPartCode(codes[i]);
      part.setPricePrepareItemId(prep.getId());
      part.setUnitPrice(result.unitPrice());
      part.setQty(prep.getQuantity());
      part.setAmount(prep.getAmount());
      part.setPriceSource(result.priceSource());
      part.setBusinessUnitType("COMMERCIAL");
      storedParts.insert(part);
    }
    var version = new QuoteCostRunVersion();
    version.setId(90001L);
    version.setCostRunNo(run);
    version.setOaNo(run);
    version.setOaFormItemId(90001L);
    version.setProductCode(run + "-TOP");
    version.setPricingMonth("2026-11");
    version.setBusinessUnitType("COMMERCIAL");
    assertThat(traceService.rebuildForVersion(version)).isEqualTo(3);
    var query = com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
        .eq(CostRunTraceSnapshot::getCostRunNo, run)
        .orderByAsc(CostRunTraceSnapshot::getPartItemId);
    var saved = traces.selectList(query);
    for (int i = 0; i < codes.length; i++) {
      assertThat(saved.get(i).getSourceType()).isEqualTo(SettlementPriceReader.RESULT_REF_TYPE);
      var captured = json.readTree(saved.get(i).getSourceSnapshotJson());
      assertThat(captured.path("sourceTable").asText()).isEqualTo("lp_settlement_price");
      assertThat(captured.path("rule").path("materialCode").asText()).isEqualTo(codes[i]);
      assertThat(captured.path("mode").asText()).isEqualTo(modes[i]);
      assertThat(captured.path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
      if (i == 2) {
        assertThat(captured.path("unitPrice").decimalValue()).isEqualByComparingTo("4.841024707965");
        assertThat(captured.path("base").path("priceMonth").asText()).isEqualTo("2026-10");
        assertThat(saved.get(i).getFormulaSnapshotJson()).contains("SETTLEMENT_CU_ZN_V1", "copperWeightG", "taxDivisor");
      }
    }
    var before = saved.stream().map(CostRunTraceSnapshot::getSourceSnapshotJson).toList();
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("COMMERCIAL", "2026-11"), "110", "26");
    var updated = reader.resolve(new SettlementPriceRequest("COMMERCIAL", codes[2], "2026-11", org, SettlementPriceType.SETTLE_LINKED));
    assertThat(updated.unitPrice()).isEqualByComparingTo("4.888708672566");
    var givenRule = prices.effective(new SettlementScope("COMMERCIAL", "2026-11"), codes[1], SettlementPriceType.SETTLE_LINKED).getFirst();
    SettlementRuleTestSupport.importWeights(imports, new SettlementScope("COMMERCIAL", givenRule.getPriceMonth()), codes[1], 8, 2);
    var nowFormula = reader.resolve(new SettlementPriceRequest("COMMERCIAL", codes[1], "2026-11", org, SettlementPriceType.SETTLE_LINKED));
    assertThat(json.readTree(nowFormula.evidence().inputSnapshotJson()).path("mode").asText()).isEqualTo("LINKED_FORMULA");
    byte[] revised;
    try (var original = getClass().getResourceAsStream("/settlement/original-20261017.xls");
         var workbook = WorkbookFactory.create(original);
         var output = new java.io.ByteArrayOutputStream()) {
      for (int rowNo : new int[] {38, 39}) {
        var row = workbook.getSheetAt(0).getRow(rowNo);
        row.getCell(3).setCellValue(5);
        row.getCell(5).setCellValue(6);
        row.getCell(8).setCellValue(6);
      }
      workbook.write(output);
      revised = output.toByteArray();
    }
    var october = new SettlementScope("COMMERCIAL", "2026-10");
    imports.confirm(revised, "finance-correction.xls", october,
        imports.preview(revised, "finance-correction.xls", october).fingerprint(), "finance");
    assertThat(reader.resolve(new SettlementPriceRequest("COMMERCIAL", codes[0], "2026-11", org, SettlementPriceType.SETTLE_FIXED)).unitPrice())
        .isEqualByComparingTo("6");
    assertThat(traces.selectList(query).stream().map(CostRunTraceSnapshot::getSourceSnapshotJson).toList()).isEqualTo(before);
    // 即使重新组装底稿，也必须使用价格准备保存的输入，不能追到现行基价。
    var rebuilt = traceBuilder.build(version);
    for (int i = 0; i < before.size(); i++)
      assertThat(json.readTree(rebuilt.get(i).getSourceSnapshotJson())).isEqualTo(json.readTree(before.get(i)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"210", "220"})
  void costingAndTechnicalChecksKeepSourceIdentityAndAmounts(String org) throws Exception {
    String oa = "STP-PART-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String[] codes = {"809633", "203259534", "203240646"};
    String[] values = {"4.1658", "4.5114", "4.841024707965"};
    var quotation = quotation(oa, oa + "-TOP", org);
    var solderItems = new java.util.ArrayList<TechnicalDataSupplementContent.SolderItem>();
    var plans = new java.util.ArrayList<PricePreparePlanItem>();
    for (int i = 0; i < codes.length; i++) {
      var row = new BomCostingRow();
      row.setOaNo(oa);
      row.setOaFormItemId(quotation.getId());
      row.setTopProductCode(oa + "-TOP");
      row.setMaterialCode(codes[i]);
      row.setUnit("只");
      row.setLevel(1);
      row.setPath("/" + oa + "/" + codes[i] + "/");
      row.setQtyPerParent(new BigDecimal("3"));
      row.setQtyPerTop(new BigDecimal("3"));
      row.setIsCostingRow(1);
      row.setPriceOrgCode(org);
      row.setMaterialOrganizationCode("210".equals(org) ? "COMMERCIAL" : "PLATE");
      row.setBusinessUnitType("COMMERCIAL");
      row.setPeriodMonth("2026-11");
      row.setAsOfDate(AS_OF.toLocalDate());
      row.setRawVersionEffectiveFrom(AS_OF.toLocalDate().minusMonths(1));
      row.setBuiltAt(AS_OF);
      row.setBuildBatchId(oa);
      bomRows.insert(row);
      var plan = new PricePreparePlanItem();
      plan.setMaterialCode(codes[i]);
      plan.setTopProductCode(row.getTopProductCode());
      plan.setBomRow(row);
      plans.add(plan);
      solderItems.add(new TechnicalDataSupplementContent.SolderItem(
          codes[i], codes[i], null, new BigDecimal("3"), "只", "隔离测试", codes[i], null));
    }
    var context = CostRunContext.quote(oa, quotation.getId(), oa + "-TOP",
        null, null, "COMMERCIAL", "2026-11", AS_OF, oa);
    context.setPriceOrgCode(org);
    context.setMaterialOrganizationCode("210".equals(org) ? "COMMERCIAL" : "PLATE");
    var actual = costParts.listByOaNo(oa, AS_OF.toLocalDate(), context, false, ignored -> {});
    var previousSecurity = SecurityContextHolder.getContext();
    try {
      var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
          "finance", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("*:*:*")));
      authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
      var security = SecurityContextHolder.createEmptyContext();
      security.setAuthentication(authentication);
      SecurityContextHolder.setContext(security);
      var defaultContext = costParts.listByOaNo(oa, AS_OF.toLocalDate(), null, false, ignored -> {});
      assertThat(defaultContext).extracting(CostRunPartItemDto::getUnitPrice)
          .containsExactlyElementsOf(actual.stream().map(CostRunPartItemDto::getUnitPrice).toList());
    } finally {
      SecurityContextHolder.setContext(previousSecurity);
    }
    var task = new QuoteTechTask();
    task.setOaNo(oa);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode(org);
    var product = new QuoteTechProduct();
    product.setOaFormItemId(quotation.getId());
    product.setAccountingMonth("2026-11");
    var checks = technicalPrices.check(task, product,
        new TechnicalDataSupplementContent.Solder(solderItems, "MANUAL", null));
    assertThat(actual).hasSize(3);
    assertThat(checks).hasSize(3);
    var prepared = normalPreparation.prepareBatch(oa, "COMMERCIAL", "2026-11", AS_OF,
        new PricePrepareScenarioContext(QuotePriceScenarioType.FINANCE_QUOTE_BASE,
            "STP-GROUP", "STP-SOURCE", Map.of("Cu", new BigDecimal("999")),
            new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(quotation.getId()).forOrganization(org)), plans, true);
    for (int i = 0; i < codes.length; i++) {
      assertThat(actual.get(i).getPartCode()).isEqualTo(codes[i]);
      assertThat(actual.get(i).getUnitPrice()).isEqualByComparingTo(values[i]);
      assertThat(actual.get(i).getAmount()).isEqualByComparingTo(new BigDecimal(values[i]).multiply(new BigDecimal("3")));
      assertThat(actual.get(i).getPriceType()).isEqualTo(i == 0 ? "结算固定价" : "结算联动价");
      assertThat(checks.get(i).status()).isEqualTo("OK");
      assertThat(checks.get(i).unitPrice()).isEqualByComparingTo(values[i]);
      assertThat(checks.get(i).priceType()).isEqualTo("NORMAL");
      assertThat(prepared.get(plans.get(i)).getStatus()).isEqualTo("READY");
      assertThat(prepared.get(plans.get(i)).getUnitPrice()).isEqualByComparingTo(values[i]);
      assertThat(prepared.get(plans.get(i)).getAmount()).isEqualByComparingTo(actual.get(i).getAmount());
      assertThat(prepared.get(plans.get(i)).getResultRefType()).isEqualTo("MONTHLY_PRICE");
      assertThat(settlementEvidence(prepared.get(plans.get(i)).getInputSnapshotJson()).path("rule").path("materialCode").asText()).isEqualTo(codes[i]);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "210,809633,4.1658,FIXED_GIVEN,结算固定价",
      "220,809633,4.1658,FIXED_GIVEN,结算固定价",
      "210,203259534,4.5114,LINKED_GIVEN,结算联动价",
      "220,203259534,4.5114,LINKED_GIVEN,结算联动价",
      "210,203240646,4.841024707965,LINKED_FORMULA,结算联动价",
      "220,203240646,4.841024707965,LINKED_FORMULA,结算联动价"})
  void manufacturingReadAndPreparationUseAllSettlementModesWithoutOrdinaryCopperOverride(
      String org, String code, String expected, String mode, String type) throws Exception {
    String oaNo = "STP-READ-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    var quotation = quotation(oaNo, oaNo, org);
    var oa = PricePrepareScenarioContext.oaLocked(quotation.getId(), org);
    var finance = new PricePrepareScenarioContext(QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        "STP-GROUP", "STP-SOURCE", Map.of("Cu", new BigDecimal("999")),
        new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(quotation.getId()).forOrganization(org));
    var read = materials.calculateMaterialUnitPrice(code, "2026-11", AS_OF.toLocalDate(), AS_OF,
        oaNo, "COMMERCIAL", oa, MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    var prepared = materials.calculateMaterialUnitPrice(code, "2026-11", AS_OF.toLocalDate(), AS_OF,
        oaNo, "COMMERCIAL", finance, MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    for (var result : java.util.List.of(read, prepared)) {
      assertThat(result.getStatus()).describedAs(result.getRemark()).isEqualTo("OK");
      assertThat(result.getPriceType()).isEqualTo("NORMAL");
      assertThat(result.getUnitPrice()).isEqualByComparingTo(expected);
      var evidence = settlementEvidence(result.getEvidence().inputSnapshotJson());
      assertThat(evidence.path("mode").asText()).isEqualTo(mode);
      assertThat(evidence.path("sourceTable").asText()).isEqualTo("lp_settlement_price");
      assertThat(evidence.path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
    }
    assertThat(settlementEvidence(read.getEvidence().inputSnapshotJson()).path("rule").path("id"))
        .isEqualTo(settlementEvidence(prepared.getEvidence().inputSnapshotJson()).path("rule").path("id"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, code)).isZero();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"210", "220"})
  void packagingReadsRealStructureAndPricesAllThreeModesWithCorrectQuantity(String org) throws Exception {
    String packageCode = "STP-PKG-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    var quotation = quotation(packageCode, packageCode + "-TOP", org);
    var snapshot = new PackageComponentSnapshot();
    snapshot.setPackageMaterialCode(packageCode);
    snapshot.setPackageMaterialName("结算包装");
    snapshot.setPriceOrgCode(org);
    snapshot.setPeriodMonth("2026-11");
    snapshot.setSourceTopProductCode(packageCode + "-TOP");
    snapshot.setStatus("NORMAL");
    snapshot.setSourceType("BOM");
    snapshot.setBusinessUnitType("COMMERCIAL");
    snapshot.setPackageParentBaseQty(BigDecimal.ONE);
    packageSnapshots.insert(snapshot);
    String[] codes = {"809633", "203259534", "203240646"};
    String[] expected = {"4.1658", "4.5114", "4.841024707965"};
    String[] modes = {"FIXED_GIVEN", "LINKED_GIVEN", "LINKED_FORMULA"};
    for (int i = 0; i < codes.length; i++) {
      var child = new PackageComponentSnapshotDetail();
      child.setSnapshotId(snapshot.getId());
      child.setPackageMaterialCode(packageCode);
      child.setPeriodMonth("2026-11");
      child.setLineNo(i + 1);
      child.setChildMaterialCode(codes[i]);
      child.setChildMaterialName(codes[i]);
      child.setBusinessUnitType("COMMERCIAL");
      child.setQtyPerParent(new BigDecimal("2"));
      child.setChildParentBaseQty(BigDecimal.ONE);
      packageDetails.insert(child);
    }
    var request = new PackagePriceRequest();
    request.setPackageMaterialCode(packageCode);
    request.setTopProductCode(snapshot.getSourceTopProductCode());
    request.setPeriodMonth("2026-11");
    request.setOaNo(packageCode);
    request.setOaFormItemId(quotation.getId());
    request.setQuoteNo(packageCode);
    request.setBusinessUnitType("COMMERCIAL");
    request.setPriceOrgCode(org);
    request.setPriceAsOfTime(AS_OF);
    request.setAsOfDate(AS_OF.toLocalDate());
    request.setCalcBatchId(packageCode);
    request.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("999"), "SUS304", new BigDecimal("9999")));
    var read = packages.calculatePrice(request);
    var prepared = packages.ensurePrice(request);
    for (var result : java.util.List.of(read, prepared)) {
      assertThat(result.isComplete()).describedAs(result.getWarnings().toString()).isTrue();
      assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("27.03644942");
      for (int i = 0; i < codes.length; i++) {
        var detail = result.getDetails().get(i);
        assertThat(detail.getChildMaterialCode()).isEqualTo(codes[i]);
        assertThat(detail.getChildUnitPrice()).isEqualByComparingTo(expected[i]);
        assertThat(detail.getPriceType()).isEqualTo("NORMAL");
        assertThat(settlementEvidence(detail.getInputSnapshotJson()).path("mode").asText()).isEqualTo(modes[i]);
      }
    }
    var stored = packages.getPriceDetail(prepared.getPrice().getId());
    assertThat(stored.getPrice().getTotalPrice()).isEqualByComparingTo("27.03644942");
    assertThat(stored.getDetails()).hasSize(3);
    // 此处是只读候选计算；报价准备捕获本次子件完整依据，正式月价采用由应用入口负责。
    var bom = new BomCostingRow();
    bom.setOaFormItemId(quotation.getId());
    bom.setPath("/" + packageCode + "/pack/");
    bom.setBusinessUnitType("COMMERCIAL");
    bom.setPriceOrgCode(org);
    bom.setQtyPerTop(new BigDecimal("3"));
    var plan = new PricePreparePlanItem();
    plan.setMaterialCode(packageCode);
    plan.setTopProductCode(snapshot.getSourceTopProductCode());
    plan.setBomRow(bom);
    var quote = packagePreparation.prepare(packageCode, packageCode, "2026-11", AS_OF, "主制造", "U9", plan);
    assertThat(quote.getStatus()).describedAs(quote.getMessage()).isEqualTo("READY");
    assertThat(quote.getAmount()).isEqualByComparingTo("81.10934826");
    var captured = json.readTree(quote.getInputSnapshotJson());
    for (int i = 0; i < modes.length; i++) {
      assertThat(settlementEvidence(captured.path("components").get(i).path("inputs").toString()).path("mode").asText()).isEqualTo(modes[i]);
      assertThat(settlementEvidence(captured.path("components").get(i).path("inputs").toString()).path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
    }
    var traceVersion = storeTraceInput(packageCode, org, "PACKAGE_COMPONENT", "PACKAGE_PRICE",
        prepared.getPrice().getId(), quote.getUnitPrice(), quote.getAmount(), quote.getInputSnapshotJson());
    assertThat(traceService.rebuildForVersion(traceVersion)).isEqualTo(1);
    var frozen = traceBuilder.build(traceVersion).getFirst();
    var children = json.readTree(frozen.getChildrenJson());
    assertThat(children).hasSize(3);
    for (int i = 0; i < modes.length; i++) {
      assertThat(children.get(i).path("materialCode").asText()).isEqualTo(codes[i]);
      assertThat(settlementEvidence(children.get(i).path("inputs").toString()).path("mode").asText()).isEqualTo(modes[i]);
      assertThat(children.get(i).path("unitPrice").decimalValue()).isEqualByComparingTo(expected[i]);
    }
    SettlementFinanceTestSupport.publish(finance, new SettlementScope("COMMERCIAL", "2026-11"), "110", "26");
    // One in-flight preparation keeps its captured candidates. A new operation with no adopted
    // monthly fact reads the newly published source; old captured quotation evidence stays intact.
    assertThat(packages.ensurePrice(request).getPrice().getTotalPrice()).isEqualByComparingTo("27.03644942");
    request.setMonthlyPrices(new com.sanhua.marketingcost.service.pricing.monthly.QuotationPriceSession.Scope());
    assertThat(packages.ensurePrice(request).getPrice().getTotalPrice()).isEqualByComparingTo("27.13181735");
    assertThat(traceBuilder.build(traceVersion).getFirst().getChildrenJson()).isEqualTo(frozen.getChildrenJson());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN ('809633','203259534','203240646')", Integer.class)).isZero();
  }

  private OaFormItem quotation(String oaNo, String productCode, String organization) {
    var form = new OaForm(); form.setOaNo(oaNo); form.setBusinessUnitType("COMMERCIAL"); forms.insert(form);
    var item = new OaFormItem(); item.setOaFormId(form.getId()); item.setMaterialNo(productCode);
    item.setProductName("220".equals(organization) ? "板式换热器" : "商用测试产品");
    item.setBusinessUnitType("COMMERCIAL"); formItems.insert(item);
    for (String code : java.util.List.of("809633", "203259534", "203240646")) {
      var master = new MaterialMasterRaw(); master.setMaterialCode(code); master.setUnit("只");
      master.setOrganizationCode("210".equals(organization) ? "COMMERCIAL" : "PLATE");
      master.setImportBatchId(oaNo); master.setActiveFlag(1); master.setGlobalSeg3TheoreticalNetWeight("10");
      rawMaterials.insert(master);
    }
    return item;
  }

  private com.fasterxml.jackson.databind.JsonNode settlementEvidence(String input) throws Exception {
    var prepared = monthlyCodec.decode(input,
        com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice.class);
    assertThat(prepared.selection().fact().type().name()).isEqualTo("NORMAL");
    var source = json.readTree(prepared.selection().fact().evidence().json());
    assertThat(source.path("sourceKind").asText()).isIn("SETTLE_FIXED", "SETTLE_LINKED");
    return source.path("source");
  }

  private QuoteCostRunVersion storeTraceInput(
      String code, String org, String kind, String refType, Long refId,
      BigDecimal unitPrice, BigDecimal amount, String inputs) {
    String run = "STP-CHILD-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    var prep = new PricePrepareItem();
    prep.setPrepareNo(run);
    prep.setOaNo(run);
    prep.setOaFormItemId(90001L);
    prep.setTopProductCode(run);
    prep.setMaterialCode(code);
    prep.setItemType(kind);
    prep.setPeriodMonth("2026-11");
    prep.setPriceOrgCode(org);
    prep.setBusinessUnitType("COMMERCIAL");
    prep.setStatus("READY");
    prep.setUnitPrice(unitPrice);
    prep.setQuantity(new BigDecimal("3"));
    prep.setAmount(amount);
    prep.setPriceSource(kind);
    prep.setResultRefType(refType);
    prep.setResultRefId(refId);
    prep.setInputSnapshotJson(inputs);
    preparations.insert(prep);
    var part = new CostRunPartItem();
    part.setOaNo(run);
    part.setCostRunNo(run);
    part.setProductCode(run);
    part.setPartCode(code);
    part.setPricePrepareItemId(prep.getId());
    part.setUnitPrice(unitPrice);
    part.setQty(prep.getQuantity());
    part.setAmount(amount);
    part.setPriceSource(kind);
    part.setBusinessUnitType("COMMERCIAL");
    storedParts.insert(part);
    var version = new QuoteCostRunVersion();
    version.setId(90001L);
    version.setCostRunNo(run);
    version.setOaNo(run);
    version.setOaFormItemId(90001L);
    version.setProductCode(run);
    version.setPricingMonth("2026-11");
    version.setBusinessUnitType("COMMERCIAL");
    return version;
  }
}
