package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Actual source, monthly lookup and recognition paths; writes belong only to the disposable MySQL fixture. */
@Tag("integration")
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false"})
class MonthlyPriceTypeRecognitionIntegrationTest extends OriginalPricingIntegrationTestBase {
  private static final java.nio.file.Path FILES = temporaryFiles();
  private static java.nio.file.Path temporaryFiles() {
    try { return java.nio.file.Files.createTempDirectory("mpp12-recognition-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @org.springframework.test.context.DynamicPropertySource
  static void householdFiles(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("price.household.workbooks-root", FILES::toString);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomCostingRowMapper bomRows;
  @Autowired QuotePriceTypeRecognitionService recognition;
  @Autowired MonthlyMaterialPrices monthly;
  @Autowired HouseholdInquiryService inquiries;
  @Autowired com.sanhua.marketingcost.mapper.BomRawHierarchyMapper rawHierarchy;
  @Autowired com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper effectiveNodes;
  @Autowired com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper preparationRecords;
  @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway monthlyU9;
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;

  @BeforeEach void tenant() {
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("MPP12", "", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @AfterEach void clearTenant() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  @Test void adoptedFixedTypeRemainsRecognizedAfterItsOriginalSourceIsDeleted() {
    var f = fixture(false, new BigDecimal("2.5"));
    fixed(f);
    var before = counts(f);
    var candidate = recognition.getRecognition(f.oa(), f.item(), f.month()).getRows().getFirst();
    assertThat(candidate.getTypeStatus()).isEqualTo("RECOGNIZED");
    assertThat(candidate.getPriceType()).isEqualTo("固定价");
    assertThat(candidate.getPriceTypeSource()).isEqualTo("FORMAL_PRICE_SOURCE");
    assertThat(counts(f)).isEqualTo(before);
    adopt(f);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", f.code());
    before = counts(f);
    var saved = recognition.getRecognition(f.oa(), f.item(), f.month()).getRows().getFirst();
    assertThat(saved.getTypeStatus()).isEqualTo("RECOGNIZED");
    assertThat(saved.getPriceType()).isEqualTo("固定价");
    assertThat(saved.getPriceTypeSource()).isEqualTo("MONTHLY_PRICE");
    assertThat(counts(f)).isEqualTo(before);
  }

  @Test void householdWholeParentUsesItsSavedTypeWithoutExpandingMissingChildren() throws Exception {
    var f = fixture(true, new BigDecimal("2.5"));
    household(f);
    var before = counts(f);
    var preview = recognition.getRecognition(f.oa(), f.item(), f.month()).getRows().getFirst();
    assertThat(preview.getTypeStatus()).isEqualTo("RECOGNIZED");
    assertThat(preview.getPriceType()).isEqualTo("家用部件询价");
    assertThat(preview.getChildren()).isEmpty();
    assertThat(preview.getPriceTypeSource()).isEqualTo("FORMAL_PRICE_SOURCE");
    assertThat(counts(f)).isEqualTo(before);
    adopt(f);
    before = counts(f);
    var saved = recognition.getRecognition(f.oa(), f.item(), f.month()).getRows().getFirst();
    assertThat(saved.getPriceType()).isEqualTo("家用部件询价");
    assertThat(saved.getChildren()).isEmpty();
    assertThat(saved.getPriceTypeSource()).isEqualTo("MONTHLY_PRICE");
    assertThat(counts(f)).isEqualTo(before);
  }

  @Test void missingPriceRemainsARealGapWithoutAdoption() {
    var missing = fixture(false, BigDecimal.ONE);
    var before = counts(missing);
    var result = recognition.getRecognition(missing.oa(), missing.item(), missing.month());
    assertThat(result.getRows()).singleElement().satisfies(row -> {
      assertThat(row.getTypeStatus()).isEqualTo("NO_SOURCE");
      assertThat(row.getPriceType()).isNull();
      assertThat(row.getMessage()).isNotBlank();
    });
    assertThat(result.getSummary().getMissingSourceCount()).isEqualTo(1);
    assertThat(counts(missing)).isEqualTo(before);

  }

  private record Fixture(String oa, long item, String code, String month, LocalDateTime asOf, BigDecimal quantity) {}
  private Fixture fixture(boolean household, BigDecimal quantity) {
    String key = "MPP12-TYPE-" + UUID.randomUUID().toString().substring(0, 8);
    String code = key + "-PART";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(1).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,accounting_period_month) VALUES(?,'COMMERCIAL',80000,?)", key, month);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, key, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    var sourceRows = new ArrayList<BomRawHierarchy>();
    for (int level = 0; level < (household ? 3 : 2); level++) {
      String material = level == 0 ? key : level == 1 ? code : key + "-CHILD";
      String shape = level == 0 ? "制造件" : household && level == 1 ? "虚拟" : "采购件";
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,shape_attr,main_category_code,main_category_name) VALUES(?,?,'件','COMMERCIAL',?,1,?,?,?)",
          material, material, key, shape, level == 0 ? "11" : household && level == 1 ? "15155" : "15",
          household && level == 1 ? "包装组件" : "普通物料");
      var raw = new BomRawHierarchy(); raw.setTopProductCode(key); raw.setParentCode(level < 2 ? key : code);
      raw.setMaterialCode(material); raw.setMaterialName(material); raw.setLevel(level); raw.setSortSeq(level);
      raw.setPath("/" + key + "/" + (level == 0 ? "" : code + "/") + (level < 2 ? "" : material + "/"));
      raw.setQtyPerParent(level == 1 ? quantity : BigDecimal.ONE); raw.setQtyPerTop(level == 0 ? BigDecimal.ONE : quantity);
      raw.setShapeAttr(shape); raw.setBomPurpose("主制造"); raw.setBomVersion("MPP12"); raw.setBomStatus("已核准");
      raw.setEffectiveFrom(date.toLocalDate()); raw.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1));
      raw.setIsLeaf(level == (household ? 2 : 1) ? 1 : 0); raw.setU9IsCostFlag(1); raw.setSourceType("U9");
      raw.setSourceImportBatchId(key); raw.setBuildBatchId(key); raw.setBuiltAt(date);
      raw.setPriceOrgCode("210"); raw.setBusinessUnitType("COMMERCIAL");
      if (household && level == 1) { raw.setMaterialCategory1("15155"); raw.setMaterialCategory2("包装组件"); }
      rawHierarchy.insert(raw); sourceRows.add(raw);
    }
    String build = freeze(form, item, key, month, date, sourceRows, Map.of());
    costingBoms.buildFromEffectiveBom(item, build);
    return new Fixture(key, item, code, month, date, quantity);
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


  private void fixed(Fixture f) {
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,supplier_name,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,created_at,updated_at) "
        + "VALUES('210',?,?,'件',11.3,0,'MPP12-S','MPP12 supplier',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','MPP12-type-fixture',NOW(),NOW())",
        f.code(), f.code(), f.asOf().toLocalDate());
  }

  private void adopt(Fixture f) {
    var context = new MonthlyMaterialPrices.Context("COMMERCIAL", f.oa(), f.item(), f.oa(), YearMonth.parse(f.month()), f.asOf(), "MPP12", "210");
    var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(), "COMMERCIAL", f.code(), null,
        "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), f.quantity());
    var candidate = monthly.resolveCandidates(context, Set.of(request)).values().get(request);
    assertThat(candidate).isNotNull();
    monthly.adopt(context, new MonthlyAdoptionPlan(Map.of(request, candidate.selection()),
        List.of(new MonthlyAdoptionPlan.Use("/" + f.oa() + "/" + f.code() + "/", request, f.quantity())), Set.of()));
  }

  private List<Integer> counts(Fixture f) {
    return List.of(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, f.code()),
        jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch WHERE oa_no=?", Integer.class, f.oa()),
        jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item WHERE oa_no=?", Integer.class, f.oa()),
        jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_gap WHERE oa_no=?", Integer.class, f.oa()));
  }

  private void household(Fixture f) throws Exception {
    try (var stream = getClass().getResourceAsStream("/household-inquiry/real-20261023.xls");
         var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);
         var bytes = new java.io.ByteArrayOutputStream()) {
      var sheet = book.getSheet("10月线圈询价");
      for (int index = sheet.getLastRowNum(); index > 2; index--)
        if (sheet.getRow(index) != null) sheet.removeRow(sheet.getRow(index));
      sheet.getRow(2).getCell(0).setCellValue(f.code()); sheet.getRow(2).getCell(4).setCellValue("20.9853"); book.write(bytes);
      var preview = inquiries.preview("COMMERCIAL", "MPP12", bytes.toByteArray(), "mpp12-recognition.xls", f.month(),
          new HouseholdInquiryModels.PriceContext("件", "CNY", TaxBasis.EXCLUDED));
      inquiries.publish("COMMERCIAL", "MPP12", new HouseholdInquiryModels.PublishRequest(
          preview.previewId(), preview.publishRequestId(), preview.previewFingerprint()));
    }
  }
}
