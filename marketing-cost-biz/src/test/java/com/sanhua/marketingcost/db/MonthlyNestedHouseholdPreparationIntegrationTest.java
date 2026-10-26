package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Original-tree household cuts remain independent inside the existing package calculator. */
@Tag("integration")
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false"})
class MonthlyNestedHouseholdPreparationIntegrationTest extends OriginalPricingIntegrationTestBase {
  static final java.nio.file.Path HOUSEHOLD_FILES = temporaryHouseholdFiles();
  private static java.nio.file.Path temporaryHouseholdFiles() {
    try { return java.nio.file.Files.createTempDirectory("mpp12-nested-household-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @org.springframework.test.context.DynamicPropertySource
  static void householdFiles(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("price.household.workbooks-root", HOUSEHOLD_FILES::toString);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired PricePrepareService preparations;
  @Autowired FinancePricePrepareService financePreparations;
  @Autowired QuoteProductBomCostingBuildService costingBoms;
  @Autowired BomCostingRowMapper bomRows;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired QuoteEffectiveBomNodeMapper effectiveNodes;
  @Autowired QuoteBomPreparationRecordMapper preparationRecords;
  @Autowired PackageComponentSnapshotMapper packageSnapshots;
  @Autowired PackageComponentSnapshotDetailMapper packageDetails;
  @Autowired BomSettlementRuleMapper settlementRules;
  @Autowired BomU9SourceMapper u9Rows;
  @Autowired MaterialScrapRefMapper scraps;
  @Autowired PricePrepareItemMapper preparedRows;
  @Autowired QuoteCostRunVersionService costVersions;
  @Autowired QuoteCostRunVersionMapper versionRows;
  @Autowired MonthlyCostVersionService monthlyCosts;
  @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway monthlyU9;
  @Autowired com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService householdInquiries;

  @BeforeEach void tenant() {
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("MPP12-NESTED", "", List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
  }
  @AfterEach void clearTenant() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  @org.junit.jupiter.api.Test
  void householdBelowAnOrdinaryWholePackageChildIsOutsideTheExistingCalculatorInputs() throws Exception {
    String key = "MPP12D-" + UUID.randomUUID().toString().substring(0, 10), pack = key + "-PACK";
    String direct = key + "-DIRECT", household = key + "-HH";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, key, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    var root = raw(key, key, key, "/" + key + "/", 0, "1", "1", "制造件", false, date);
    var parent = raw(key, pack, key, root.getPath() + pack + "/", 1, "2.5", "2.5", "虚拟", false, date);
    parent.setMaterialCategory1("15155"); parent.setMaterialCategory2("包装组件");
    var child = raw(key, direct, pack, parent.getPath() + direct + "/", 2, "2", "1", "采购件", false, date);
    var deep = raw(key, household, direct, child.getPath() + household + "/", 3, "3", "3", "采购件", true, date);
    var rows = List.of(root, parent, child, deep);
    for (var row : rows) {
      rawHierarchy.insert(row);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,shape_attr,main_category_code) VALUES(?,?,'件','COMMERCIAL',?,1,?,?)",
          row.getMaterialCode(), row.getMaterialName(), key, row.getShapeAttr(), row.getMaterialCategory1());
    }
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,?,'件',7,0,'MPP12-NESTED',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", direct, direct, date.toLocalDate().withDayOfMonth(1));
    var snapshot = new PackageComponentSnapshot(); snapshot.setPackageMaterialCode(pack); snapshot.setPeriodMonth(month);
    snapshot.setPriceOrgCode("210"); snapshot.setBusinessUnitType("COMMERCIAL"); snapshot.setSourceTopProductCode(key);
    snapshot.setSourceBomPurpose("主制造"); snapshot.setSourceBomSourceType("U9"); snapshot.setStatus("NORMAL");
    snapshot.setPackageParentBaseQty(new BigDecimal("5")); packageSnapshots.insert(snapshot);
    var detail = new PackageComponentSnapshotDetail(); detail.setSnapshotId(snapshot.getId()); detail.setPackageMaterialCode(pack);
    detail.setPeriodMonth(month); detail.setLineNo(1); detail.setChildMaterialCode(direct); detail.setQtyPerParent(new BigDecimal("2"));
    detail.setChildParentBaseQty(BigDecimal.ONE); detail.setSourceHierarchyId(child.getId()); detail.setSourcePath(child.getPath());
    detail.setBusinessUnitType("COMMERCIAL"); packageDetails.insert(detail);
    String build = freeze(form, item, key, month, date, rows); publishHousehold(household, month);
    costingBoms.buildFromEffectiveBom(item, build);
    assertThat(costingBoms.previewFromEffectiveBom(item, build).rows()).singleElement().satisfies(row -> {
      assertThat(row.getMaterialCode()).isEqualTo(pack); assertThat(row.getHouseholdChildPaths()).isEmpty();
    });
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var preview = preparations.calculate(request);
    assertThat(preview.getSummary().getStatus()).as(preview.getSummary().getMessage()).isEqualTo("SUCCESS");
    assertThat(preview.getItems()).singleElement().satisfies(row -> assertThat(row.getAmount()).isEqualByComparingTo("7"));
    var saved = preparations.generate(request); assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
    assertThat(financePreparations.generateFromOa(saved.getPrepareNo()).prepareResult().getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code LIKE ?", String.class, key + "%"))
        .containsExactly(direct);
    assertThat(packageSnapshots.selectById(snapshot.getId()).getId()).as("普通包装沿原保存结构，不新建本次分支快照").isEqualTo(snapshot.getId());
  }

  @org.junit.jupiter.api.Test
  void manufacturingPreviewUsesCurrentRollupChildrenWithoutAnOldRowIdOrHouseholdPrice() throws Exception {
    String key = "MPP12M-" + UUID.randomUUID().toString().substring(0, 10);
    String parent = key + "-MAKE", rawCode = key + "-RAW", household = key + "-HH", scrap = key + "-SCRAP";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,'商用蒸发器','COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    var rows = List.of(raw(key, key, key, "/" + key + "/", 0, "1", "1", "制造件", false, date),
        raw(key, parent, key, "/" + key + "/" + parent + "/", 1, "2.5", "2.5", "制造件", false, date),
        raw(key, rawCode, parent, "/" + key + "/" + parent + "/" + rawCode + "/", 2, "0.4", "1", "采购件", true, date),
        raw(key, household, parent, "/" + key + "/" + parent + "/" + household + "/", 2, "0.3", "0.75", "采购件", true, date));
    for (var row : rows) {
      if (row.getLevel() == 2) row.setMaterialName(key + "-ROLLUP-CHILD");
      rawHierarchy.insert(row);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,shape_attr,main_category_code,global_seg_3_theoretical_net_weight) VALUES(?,?,?,'COMMERCIAL',?,1,?,?,?)",
          row.getMaterialCode(), row.getMaterialName(), row.getMaterialCode().equals(rawCode) ? "KG" : "件", key,
          row.getShapeAttr(), row.getLevel() == 0 ? "10" : "15", row.getMaterialCode().equals(parent) ? "300" : null);
    }
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,?,'KG',100,0,'MPP12-NESTED',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", rawCode, rawCode, date.toLocalDate().withDayOfMonth(1));
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) "
        + "VALUES('COMMERCIAL',?,?,'MPP12 废料','FIXED',10,'{}','IMPORT',?,?,'MPP12',NOW(),'MPP12',NOW())", month, scrap, month, "e".repeat(64));
    var mapping = new MaterialScrapRef(); mapping.setMaterialCode(rawCode); mapping.setScrapCode(scrap);
    mapping.setScrapUnit("KG"); mapping.setBusinessUnitType("COMMERCIAL"); mapping.setRatio(BigDecimal.ONE); scraps.insert(mapping);
    for (var row : rows) if (row.getLevel() == 2) {
      var child = new BomU9Source(); child.setImportBatchId(key); child.setSourceType("U9"); child.setPriceOrgCode("210");
      child.setImportedAt(date); child.setImportedBy("MPP12"); child.setParentMaterialNo(parent);
      child.setChildMaterialNo(row.getMaterialCode()); child.setChildMaterialName(row.getMaterialName());
      child.setStockUnit(row.getMaterialCode().equals(rawCode) ? "KG" : "件"); child.setBomPurpose("主制造");
      child.setBomVersion("MPP12N"); child.setChildSeq(row.getMaterialCode().equals(rawCode) ? 1 : 2);
      child.setQtyPerParent(row.getQtyPerParent()); child.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); u9Rows.insert(child);
    }
    var rule = new BomSettlementRule(); rule.setRuleCode(key); rule.setRuleName(key);
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP"); rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT"); rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_name\",\"op\":\"EQ\",\"value\":\"" + key + "-ROLLUP-CHILD\"}]}");
    rule.setMarkSubtreeCostRequired(1); rule.setPriority(1); rule.setEnabled(1); rule.setDeleted(0);
    rule.setBusinessUnitType("COMMERCIAL"); settlementRules.insert(rule);
    String build = freeze(form, item, key, month, date, rows, Map.of(rawCode, "KG"));
    costingBoms.buildFromEffectiveBom(item, build); publishHousehold(household, month);
    var current = costingBoms.previewFromEffectiveBom(item, build).rows();
    assertThat(current).hasSize(2).allSatisfy(row -> assertThat(row.getId()).isNull());
    var currentParent = current.stream().filter(row -> parent.equals(row.getMaterialCode())).findFirst().orElseThrow();
    assertThat(currentParent.getCurrentSubRefs()).extracting(BomCostingRowSubRef::getSubMaterialCode).containsExactly(rawCode);
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var preview = preparations.calculate(request);
    assertThat(preview.getSummary().getStatus()).as(preview.getSummary().getMessage()).isEqualTo("SUCCESS");
    var make = preview.getItems().stream().filter(row -> parent.equals(row.getMaterialCode())).findFirst().orElseThrow();
    assertThat(make.getUnitPrice()).isEqualByComparingTo("39"); assertThat(make.getAmount()).isEqualByComparingTo("97.5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code LIKE ?", Integer.class, key + "%")).isZero();
    var saved = preparations.generate(request); assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
    var finance = financePreparations.generateFromOa(saved.getPrepareNo());
    assertThat(finance.prepareResult().getStatus()).as(finance.prepareResult().getMessage()).isEqualTo("SUCCESS");
    var codec = new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var prepared = preparedRows.selectOne(Wrappers.lambdaQuery(PricePrepareItem.class)
        .eq(PricePrepareItem::getPrepareNo, saved.getPrepareNo()).eq(PricePrepareItem::getMaterialCode, parent));
    var inputs = new MonthlyPreparationEvidence(codec).read(prepared);
    assertThat(inputs.uses()).hasSize(2).noneSatisfy(use -> assertThat(use.request().family().materialCode()).isEqualTo(household));
    assertThat(inputs.uses().stream().filter(use -> rawCode.equals(use.request().family().materialCode())).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("1");
    assertThat(inputs.uses().stream().filter(use -> scrap.equals(use.request().family().materialCode())).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("0.25");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code LIKE ?", String.class, key + "%"))
        .containsExactlyInAnyOrder(rawCode, scrap, household);
    assertThat(bomRows.selectList(Wrappers.lambdaQuery(BomCostingRow.class).eq(BomCostingRow::getOaFormItemId, item)))
        .hasSize(2).anySatisfy(row -> assertThat(row.getSettlementRowType()).isEqualTo("HOUSEHOLD_INQUIRY"));
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true"})
  void repeatedHouseholdChildrenRemainIndependentOfPackageDenominatorsAndOrdinaryAmounts(boolean allHousehold, boolean sameParent) throws Exception {
    String key = "MPP12N-" + UUID.randomUUID().toString().substring(0, 10);
    String household = key + "-HH", rawCode = key + "-RAW", covered = key + "-COVERED";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, key, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    var rows = new ArrayList<BomRawHierarchy>();
    rows.add(raw(key, key, key, "/" + key + "/", 0, "1", "1", "制造件", false, date));
    for (int index = 0; index < 2; index++) {
      String parent = key + "-PACK" + (sameParent ? "" : index), parentPath = "/" + key + "/" + key + "-PACK" + index + "/";
      String parentQuantity = index == 0 ? "2.5" : "2";
      var pack = raw(key, parent, key, parentPath, 1, parentQuantity, parentQuantity, "虚拟", false, date);
      pack.setSortSeq(index + 1);
      pack.setMaterialCategory1("15155"); pack.setMaterialCategory2("包装组件"); rows.add(pack);
      var hh = raw(key, household, parent, parentPath + household + "/", 2,
          index == 0 ? "3" : "5", index == 0 ? "0.75" : "1.25", "虚拟", false, date);
      hh.setSortSeq(10 + index);
      hh.setMaterialCategory1("15155"); hh.setMaterialCategory2("包装组件"); rows.add(hh);
      rows.add(raw(key, covered, household, hh.getPath() + covered + "/", 3, "1", hh.getQtyPerTop().toPlainString(), "采购件", true, date));
      if (!allHousehold) {
        var ordinary = raw(key, rawCode, parent, parentPath + rawCode + "/", 2,
            index == 0 ? "12" : "6", index == 0 ? "2" : "1", "采购件", true, date);
        ordinary.setSortSeq(20 + index); rows.add(ordinary);
      }
    }
    for (var row : rows) {
      row.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1)); rawHierarchy.insert(row);
      if (sameParent && row.getLevel() > 0 && row.getLevel() < 3) {
        var source = new BomU9Source(); source.setImportBatchId(key); source.setSourceType("U9");
        source.setPriceOrgCode("210"); source.setImportedAt(date); source.setImportedBy("MPP12");
        source.setParentMaterialNo(row.getParentCode()); source.setChildMaterialNo(row.getMaterialCode());
        source.setStockUnit("件"); source.setBomPurpose(row.getBomPurpose()); source.setBomVersion(row.getBomVersion());
        source.setChildSeq(row.getSortSeq()); source.setQtyPerParent(row.getQtyPerParent());
        source.setEffectiveFrom(row.getEffectiveFrom()); source.setEffectiveTo(row.getEffectiveTo());
        source.setParentBaseQty(new BigDecimal(row.getLevel() == 1 ? (row.getSortSeq() == 1 ? "5" : "4")
            : household.equals(row.getMaterialCode()) ? "2" : "3")); u9Rows.insert(source);
      }
    }
    for (String code : rows.stream().map(BomRawHierarchy::getMaterialCode).distinct().toList()) {
      var row = rows.stream().filter(value -> code.equals(value.getMaterialCode())).findFirst().orElseThrow();
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,shape_attr,main_category_code,main_category_name) VALUES(?,?,'件','COMMERCIAL',?,1,?,?,?)",
          code, code, key, row.getShapeAttr(), row.getMaterialCategory1(), row.getMaterialCategory2());
    }
    if (!allHousehold) jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,?,'件',7,0,'MPP12-NESTED',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", rawCode, rawCode, date.toLocalDate().withDayOfMonth(1));
    String build = freeze(form, item, key, month, date, rows);
    // Saved original package structure, including both existing denominator levels.
    for (int index = 0; index < 2; index++) {
      if (sameParent && index == 1) continue;
      String parent = key + "-PACK" + (sameParent ? "" : index);
      String parentPath = "/" + key + "/" + key + "-PACK" + index + "/";
      var snapshot = new PackageComponentSnapshot();
      snapshot.setPackageMaterialCode(parent); snapshot.setPeriodMonth(month); snapshot.setPriceOrgCode("210");
      snapshot.setBusinessUnitType("COMMERCIAL"); snapshot.setSourceTopProductCode(key);
      snapshot.setSourceBomPurpose("主制造"); snapshot.setSourceBomSourceType("U9"); snapshot.setStatus("NORMAL");
      var sourceParent = rows.stream().filter(row -> parentPath.equals(row.getPath())).findFirst().orElseThrow();
      snapshot.setSourceRawHierarchyId(sourceParent.getId()); snapshot.setSourcePath(sourceParent.getPath());
      snapshot.setPackageParentBaseQty(new BigDecimal(index == 0 ? "5" : "4")); packageSnapshots.insert(snapshot);
      int sequence = 0;
      for (var row : rows) if (parent.equals(row.getParentCode()) && row.getPath().startsWith(parentPath)) {
        var detail = new PackageComponentSnapshotDetail(); detail.setSnapshotId(snapshot.getId());
        detail.setPackageMaterialCode(parent); detail.setPeriodMonth(month); detail.setLineNo(++sequence);
        detail.setChildMaterialCode(row.getMaterialCode()); detail.setChildMaterialName(row.getMaterialCode());
        detail.setQtyPerParent(row.getQtyPerParent()); detail.setQtyPerTop(row.getQtyPerTop());
        detail.setChildParentBaseQty(new BigDecimal(household.equals(row.getMaterialCode()) ? "2" : "3"));
        detail.setSourceHierarchyId(row.getId()); detail.setSourcePath(row.getPath());
        detail.setSourceParentCode(parent); detail.setBusinessUnitType("COMMERCIAL"); packageDetails.insert(detail);
      }
    }
    costingBoms.buildFromEffectiveBom(item, build);
    publishHousehold(household, month);
    var previewRows = costingBoms.previewFromEffectiveBom(item, build).rows();
    assertThat(previewRows).hasSize(allHousehold ? 2 : 4).allSatisfy(row -> assertThat(row.getId()).isNull());
    assertThat(previewRows.stream().filter(row -> household.equals(row.getMaterialCode())))
        .extracting(row -> row.getQtyPerTop().stripTrailingZeros().toPlainString()).containsExactlyInAnyOrder("0.75", "1.25");
    assertThat(bomRows.selectList(Wrappers.lambdaQuery(BomCostingRow.class).eq(BomCostingRow::getOaFormItemId, item)))
        .hasSize(sameParent ? 1 : 2).allSatisfy(row -> assertThat(row.getSettlementRowType()).isEqualTo("PACKAGE_PARENT"));
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    if (!allHousehold && !sameParent) {
      jdbc.update("UPDATE lp_price_fixed_item SET effective_to=? WHERE material_code=?", date.toLocalDate().minusDays(1), rawCode);
      try {
        var missing = preparations.calculate(request);
        assertThat(missing.getSummary().getStatus()).isEqualTo("PARTIAL");
        assertThat(missing.getGaps()).isNotEmpty().allSatisfy(gap -> assertThat(gap.getGapMaterialCode()).isEqualTo(rawCode));
      } finally {
        jdbc.update("UPDATE lp_price_fixed_item SET effective_to='2099-01-01' WHERE material_code=?", rawCode);
      }
    }
    var preview = preparations.calculate(request);
    assertThat(preview.getSummary().getStatus()).as(preview.getSummary().getMessage()).isEqualTo("SUCCESS");
    assertThat(preview.getItems()).hasSize(allHousehold ? 2 : 4);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code LIKE ?", Integer.class, key + "%")).isZero();
    assertThat(bomRows.selectList(Wrappers.lambdaQuery(BomCostingRow.class).eq(BomCostingRow::getOaFormItemId, item)))
        .hasSize(sameParent ? 1 : 2).allSatisfy(row -> assertThat(row.getSettlementRowType()).isEqualTo("PACKAGE_PARENT"));
    var saved = preparations.generate(request);
    assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
    var finance = financePreparations.generateFromOa(saved.getPrepareNo());
    assertThat(finance.prepareResult().getStatus()).as(finance.prepareResult().getMessage()).isEqualTo("SUCCESS");
    var expectedMaterials = allHousehold ? List.of(household) : List.of(household, rawCode);
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code LIKE ?", String.class, key + "%"))
        .containsExactlyInAnyOrderElementsOf(expectedMaterials);
    var customerRows = preparedRows.selectList(Wrappers.lambdaQuery(PricePrepareItem.class).eq(PricePrepareItem::getPrepareNo, saved.getPrepareNo()));
    var financialRows = preparedRows.selectList(Wrappers.lambdaQuery(PricePrepareItem.class).eq(PricePrepareItem::getPrepareNo, finance.financePrepareNo()));
    var codec = new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var evidence = new MonthlyPreparationEvidence(codec);
    var selections = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    var uses = new ArrayList<MonthlyAdoptionPlan.Use>(); var suppressed = new HashSet<String>();
    var bindings = new LinkedHashMap<String, MonthlyCostVersionService.PreparationBinding>();
    var ordinaryLines = new ArrayList<MonthlyProductCost.MaterialLine>();
    for (var customer : customerRows) {
      var captured = evidence.read(customer); var root = captured.rootPath();
      var financial = financialRows.stream().filter(value -> root.equals(evidence.read(value).rootPath())).findFirst().orElseThrow();
      selections.putAll(captured.plan().selections()); uses.addAll(captured.uses()); suppressed.addAll(captured.suppressedPaths());
      bindings.put(root, new MonthlyCostVersionService.PreparationBinding(financial.getId(), customer.getId()));
      if (household.equals(customer.getMaterialCode())) {
        assertThat(captured.uses()).hasSize(1); assertThat(captured.suppressedPaths()).hasSize(1);
      } else {
        assertThat(captured.uses()).hasSize(1).allSatisfy(use -> assertThat(use.request().family().materialCode()).isEqualTo(rawCode));
        assertThat(captured.suppressedPaths()).isEmpty();
        assertThat(customer.getInputSnapshotJson()).contains("HOUSEHOLD_EXCLUDED", "sourcePath", "householdUse");
        assertThat(customer.getAmount()).isEqualByComparingTo(root.endsWith("PACK0") ? "14" : "7");
        ordinaryLines.add(new MonthlyProductCost.MaterialLine(root, customer.getMaterialCode(), customer.getQuantity(),
            captured.prices().stream().map(price -> price.selection().monthlyPriceId()).distinct().toList(),
            financial.getAmount(), customer.getAmount(), null));
      }
    }
    var version = costVersions.createRunning(key, item, key, month, month, finance.financePrepareNo(), "COMMERCIAL");
    version.setOaPricePrepareNo(saved.getPrepareNo()); version.setFinancePricePrepareNo(finance.financePrepareNo()); versionRows.updateById(version);
    var ordinary = new MonthlyProductCost.Ordinary(ordinaryLines, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    var adoptedPlan = new MonthlyAdoptionPlan(selections, uses, suppressed);
    if (!allHousehold) {
      var financial = financialRows.stream().filter(row -> evidence.read(row).rootPath().endsWith("PACK0")).findFirst().orElseThrow();
      String originalEvidence = financial.getInputSnapshotJson();
      var json = new com.fasterxml.jackson.databind.ObjectMapper(); var damaged = json.readTree(originalEvidence);
      for (var component : damaged.path("components")) if (component.has("householdUse"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) component.path("householdUse")).put("path", key + "/unselected-household");
      jdbc.update("UPDATE lp_price_prepare_item SET input_snapshot_json=? WHERE id=?", damaged.toString(), financial.getId());
      try {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> monthlyCosts.calculateAndSave("COMMERCIAL", version.getId(),
            adoptedPlan, ordinary, null, new BigDecimal("80000"), bindings))
            .hasMessageContaining("包装排除子件没有本次独立家用采用依据");
      } finally {
        jdbc.update("UPDATE lp_price_prepare_item SET input_snapshot_json=? WHERE id=?", originalEvidence, financial.getId());
      }
    }
    var cost = monthlyCosts.calculateAndSave("COMMERCIAL", version.getId(), adoptedPlan,
        ordinary, null, new BigDecimal("80000"), bindings);
    assertThat(cost.snapshot().calculation().financeMaterialCost()).isEqualByComparingTo(allHousehold ? "0" : "21");
    assertThat(cost.snapshot().calculation().householdSubtotal()).isEqualByComparingTo("41.9706");
    assertThat(cost.snapshot().calculation().household()).hasSize(1);
    assertThat(cost.snapshot().calculation().household().getFirst().paths()).hasSize(2);
    assertThat(cost.version().getFinalQuoteAmount()).isEqualByComparingTo(allHousehold ? "41.9706" : "62.9706");
    assertThat(cost.parts()).hasSize(allHousehold ? 1 : 3);
  }

  private BomRawHierarchy raw(String top, String code, String parent, String path, int level,
      String quantity, String cumulative, String shape, boolean leaf, LocalDateTime date) {
    var raw = new BomRawHierarchy(); raw.setTopProductCode(top); raw.setMaterialCode(code); raw.setMaterialName(code);
    raw.setParentCode(parent); raw.setPath(path); raw.setLevel(level); raw.setSortSeq(level);
    raw.setQtyPerParent(new BigDecimal(quantity)); raw.setQtyPerTop(new BigDecimal(cumulative)); raw.setShapeAttr(shape);
    raw.setBomPurpose("主制造"); raw.setBomVersion("MPP12N"); raw.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1));
    raw.setBomStatus("已核准"); raw.setIsLeaf(leaf ? 1 : 0); raw.setU9IsCostFlag(1); raw.setSourceType("U9");
    raw.setSourceImportBatchId(top); raw.setBuildBatchId(top); raw.setBuiltAt(date); raw.setPriceOrgCode("210");
    raw.setBusinessUnitType("COMMERCIAL"); return raw;
  }

  private String freeze(long form, long item, String key, String month, LocalDateTime date, List<BomRawHierarchy> rows) {
    return freeze(form, item, key, month, date, rows, Map.of());
  }

  private String freeze(long form, long item, String key, String month, LocalDateTime date, List<BomRawHierarchy> rows,
      Map<String, String> units) {
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, key, month, "COMMERCIAL", key, key, null, null, "210", "COMMERCIAL", date.toLocalDate(), date));
    assertThat(original.status()).as(original.message()).isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
    var keys = new HashMap<String, String>(); rows.forEach(raw -> keys.put(raw.getPath(), "NODE-" + raw.getId()));
    String build = key + "-EFFECTIVE";
    for (var raw : rows) {
      var node = new QuoteEffectiveBomNode(); node.setOriginMonthlySnapshotId(original.monthlySnapshotId());
      node.setEffectiveVariantHash(original.structureFingerprint()); node.setBuildBatchId(build); node.setTopProductCode(key);
      node.setCostPeriodMonth(month); node.setPriceOrgCode("210"); node.setNodeKey(keys.get(raw.getPath()));
      int slash = raw.getPath().lastIndexOf('/', raw.getPath().length() - 2);
      node.setParentNodeKey(raw.getLevel() == 0 ? null : keys.get(raw.getPath().substring(0, slash + 1)));
      node.setNodeLevel(raw.getLevel()); node.setSortSeq(raw.getSortSeq()); node.setNodePath(raw.getPath());
      node.setMaterialCode(raw.getMaterialCode()); node.setMaterialName(raw.getMaterialName());
      node.setMaterialUnit(units.getOrDefault(raw.getMaterialCode(), "件"));
      node.setQtyPerParent(raw.getQtyPerParent()); node.setQtyPerTop(raw.getQtyPerTop());
      node.setEffectiveMaterialShape(com.sanhua.marketingcost.enums.QuoteMaterialShape.fromU9(raw.getShapeAttr()).name());
      node.setSourceMaterialShape(node.getEffectiveMaterialShape()); node.setShapeResolutionSource("U9");
      node.setSourceBomType("U9"); node.setSourceBomBatchId(key); node.setSourceHierarchyId(raw.getId()); node.setSourceNodePath(raw.getPath());
      effectiveNodes.insert(node);
    }
    var record = new QuoteBomPreparationRecord(); record.setOaFormId(form); record.setOaFormItemId(item); record.setOaNo(key);
    record.setQuoteProductCode(key); record.setPriceOrgCode("210"); record.setMaterialOrganizationCode("COMMERCIAL");
    record.setProductType("NON_BARE"); record.setCostPeriodMonth(month); record.setPreparationStatus("READY"); record.setActiveFlag(1);
    preparationRecords.insert(record); return build;
  }

  private void publishHousehold(String code, String month) throws Exception {
    try (var stream = getClass().getResourceAsStream("/household-inquiry/real-20261023.xls");
         var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);
         var bytes = new java.io.ByteArrayOutputStream()) {
      var sheet = book.getSheet("10月线圈询价");
      for (int index = sheet.getLastRowNum(); index > 2; index--)
        if (sheet.getRow(index) != null) sheet.removeRow(sheet.getRow(index));
      sheet.getRow(2).getCell(0).setCellValue(code); sheet.getRow(2).getCell(4).setCellValue("20.9853"); book.write(bytes);
      var preview = householdInquiries.preview("COMMERCIAL", "MPP12", bytes.toByteArray(), "mpp12-nested.xls", month,
          new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PriceContext("件", "CNY", TaxBasis.EXCLUDED));
      householdInquiries.publish("COMMERCIAL", "MPP12", new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PublishRequest(
          preview.previewId(), preview.publishRequestId(), preview.previewFingerprint()));
    }
  }
}
