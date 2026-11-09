package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class MonthlyInquiryPriceIntegrationTest {
  static final InquiryDatabaseTestSupport db = new InquiryDatabaseTestSupport();
  static MonthlyInquiryPriceSource source;
  static MonthlyMaterialPriceService prices;
  static MonthlyPriceDocumentCodec codec;

  @BeforeAll
  static void start() throws Exception {
    db.start();
    db.jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(
            new ClassPathResource("monthly-pricing/fixed-source-schema.sql"),
            new ClassPathResource("monthly-pricing/pre-v342-schema.sql"))
        .execute(db.jdbc.getDataSource());
    db.jdbc.execute(
        "CREATE TABLE lp_material_master_raw(id BIGINT AUTO_INCREMENT PRIMARY KEY,organization_code"
            + " VARCHAR(32),material_code VARCHAR(64),material_model VARCHAR(512),material_spec"
            + " VARCHAR(512),active_flag INT DEFAULT 1)");
    for (String script :
        List.of(
            "V342__monthly_material_price_storage.sql",
            "V344__monthly_material_price_single_table.sql",
            "V354__invoice_price_import.sql","V360__finance_correction_monthly_price.sql","V364__household_inquiry_header_schema.sql","V365__household_monthly_source_revision.sql","V372__monthly_price_disable.sql","V366__household_model_specification_match.sql")) {
      db.mysql.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/inquiry-monthly.sql");
      var result =
          db.mysql.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                  + " inquiry_pricing_test < /tmp/inquiry-monthly.sql");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    for (var mapper :
        List.of(
            MonthlySupplierInputMapper.class,
            MonthlyPriceStorageMapper.class,
            MonthlyPriceLinkMapper.class)) db.sql.getConfiguration().addMapper(mapper);
    codec = new MonthlyPriceDocumentCodec(db.json);
    source =
        new MonthlyInquiryPriceSource(
            db.jdbc,
            db.repository,
            db.sql.getMapper(MonthlySupplierInputMapper.class),
            new SupplierPriceSelectionService(null, null),
            codec);
    prices =
        new MonthlyMaterialPriceService(
            new MonthlyPriceStorage(
                db.sql.getMapper(MonthlyPriceStorageMapper.class),
                codec,
                db.sql.getMapper(MonthlyPriceLinkMapper.class)),
            db.manager,
            codec,
            (context, requests, dependencies) -> source.read(context, requests));
    db.publishFinance("2026-10", "CU_90000");
  }

  @AfterAll
  static void stop() {
    db.close();
  }

  @BeforeEach
  void clean() {
    db.clearInquiries();
    db.jdbc.update("DELETE FROM lp_supplier_supply_ratio");
    db.jdbc.update("DELETE FROM lp_material_master_raw");
  }

  static Context context() {
    return new Context(
        "COMMERCIAL",
        "INQUIRY-TEST",
        1L,
        "TOP",
        YearMonth.of(2026, 10),
        LocalDateTime.of(2026, 10, 31, 0, 0),
        "fixture",
        "210");
  }

  static MonthlyPriceRequest request(String code, String org, String unit) {
    return new MonthlyPriceRequest(
        new MonthlyPriceFamilyKey(
            context().month(), "COMMERCIAL", code, null, unit, "CNY", TaxBasis.EXCLUDED, null),
        org,
        NonCuConditions.empty(),
        BigDecimal.ONE);
  }

  static MonthlyPriceRequest specification(String sourceCode, String spec, String org, String unit) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context().month(), "COMMERCIAL",
        sourceCode, spec, unit, "CNY", TaxBasis.EXCLUDED, null, true), org,
        NonCuConditions.empty(), BigDecimal.ONE);
  }

  static String code() {
    return "INQ-" + UUID.randomUUID().toString().substring(0, 12);
  }

  static byte[] fixedRows(String[]... rows) throws Exception {
    try (var book = new XSSFWorkbook();
        var bytes = new ByteArrayOutputStream()) {
      var s = book.createSheet("后来改名的询价");
      var header = s.createRow(0);
      String[] labels = {"料号", "规格型号", "供方名称", "价格类型", "批量价总价（元）", "不含税价格"};
      for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
      for (int i = 0; i < rows.length; i++) {
        var r = s.createRow(i + 1);
        for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
      }
      book.write(bytes);
      return bytes.toByteArray();
    }
  }

  static String[] fixed(String code, String model, String supplier, String price) {
    return new String[] {code, model, supplier, "固定价", price, "9999"};
  }

  static void material(String code, String org, String model, String specification) {
    db.jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(organization_code,material_code,material_model,material_spec)"
            + " VALUES(?,?,?,?)",
        org,
        code,
        model,
        specification);
  }

  static void ratio(String code, String supplier, String name, String value) {
    db.jdbc.update(
        "INSERT INTO"
            + " lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted,source_batch_no)"
            + " VALUES('COMMERCIAL',?,'物料','型号',?,?,?,1,0,'inquiry-test')",
        code,
        name,
        supplier,
        new BigDecimal(value));
  }

  static Selection selected(MonthlyPriceRequest request) {
    var result = source.read(context(), Set.of(request));
    assertThat(result.problems()).isEmpty();
    return result.values().get(request).selection();
  }

  @Test
  void codeMatchesWithoutDisplayRestrictionsAndOnlyAnAbsentBomCodeUsesSpecification()
      throws Exception {
    String material = code();
    material(material, "COMMERCIAL", "MODEL-X", "SPEC-Y");
    db.importFile(
        "2026-10",
        fixedRows(fixed("OTHER", "MODEL-X", "", "20"), fixed(material, "OTHER-MODEL", "", "10")));
    assertThat(selected(request(material, "210", "只")).fact().price()).isEqualByComparingTo("10");
    String other = code();
    material(other, "COMMERCIAL", "MODEL-X", "");
    var noSource = request(other, "210", "KG");
    assertThat(source.read(context(), Set.of(noSource)).problems().get(noSource).reason())
        .as("有料号不能回退到主数据型号或规格")
        .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
    var fallback = selected(specification("OTHER", "MODEL-X", "210", "KG"));
    assertThat(fallback.fact().price()).isEqualByComparingTo("20");
    assertThat(fallback.fact().evidence().json()).contains("SPECIFICATION");
  }

  @Test
  void eachCodedMaterialUsesItsOwnSourcesAndSupplierRatios() throws Exception {
    String first = code(), second = code();
    material(first, "COMMERCIAL", "型号（X）", "");
    material(second, "PLATE", "型号(X)", "");
    db.importFile(
        "2026-10", fixedRows(fixed(first, "来源规格一", "浙江东信", "10"), fixed(first, "来源规格二", "另一供方", "20"), fixed(second, "来源规格三", "浙江东信", "10"), fixed(second, "来源规格四", "另一供方", "20")));
    ratio(first, "S001069", "浙江东信机械有限公司", "0.8");
    ratio(first, "S2", "另一供方有限公司", "0.2");
    ratio(second, "S001069", "浙江东信机械有限公司", "0.1");
    ratio(second, "S2", "另一供方有限公司", "0.9");
    var a = selected(request(first, "210", "只"));
    var b = selected(request(second, "220", "件"));
    assertThat(a.fact().price()).isEqualByComparingTo("10");
    assertThat(b.fact().price()).isEqualByComparingTo("20");
    assertThat(a.fact().evidence().json()).contains("S001069", "ABBREVIATION", "RATIO_MAX");
  }

  @Test
  void supplierUpdateRetainsOtherSuppliersAndSameBatchUsesHighestPrice() throws Exception {
    String code = code();
    ratio(code, "A", "甲有限公司", "0.8");
    ratio(code, "B", "乙有限公司", "0.2");
    db.importFile("2026-10", fixedRows(fixed(code, "", "甲", "100"), fixed(code, "", "乙", "12")));
    db.importFile(
        "2026-10", fixedRows(fixed(code, "", "甲有限公司", "10"), fixed(code, "", "甲有限公司", "11")));
    assertThat(selected(request(code, "210", "件")).fact().price()).isEqualByComparingTo("11");
    db.jdbc.update(
        "UPDATE lp_supplier_supply_ratio SET supply_ratio=IF(supplier_code='A',0.1,0.9) WHERE"
            + " material_code=?",
        code);
    assertThat(selected(request(code, "210", "件")).fact().price()).isEqualByComparingTo("12");
  }

  @Test
  void emptySupplierAndUnitPermitASingleZeroPriceAndSpecificationOnlyFallback() throws Exception {
    String code = code();
    material(code, "COMMERCIAL", "", "SPEC-ONLY");
    db.importFile("2026-10", fixedRows(fixed("", "SPEC-ONLY", "", "0")));
    var result = selected(specification("", "SPEC-ONLY", "210", "千克"));
    assertThat(result.fact().price()).isZero();
    assertThat(result.fact().evidence().json()).contains("EMPTY", "SINGLE");
  }

  @Test
  void multipleUnknownRatiosWaitAndAmbiguousSupplierNamesNeverGuessACode() throws Exception {
    String code = code();
    db.importFile("2026-10", fixedRows(fixed(code, "", "甲方", "10"), fixed(code, "", "乙方", "20")));
    var request = request(code, "210", "只");
    var context = context();
    var result = source.read(context, Set.of(request));
    assertThat(result.problems().get(request).reason())
        .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
    assertThat(context.supplierReviews().reviews()).hasSize(1);
    db.clearInquiries();
    db.importFile("2026-10", fixedRows(fixed(code, "", "浙江东信", "10")));
    ratio(code, "A", "浙江东信机械有限公司", "0.5");
    ratio(code, "B", "浙江东信配件有限公司", "0.5");
    assertThat(source.read(context(), Set.of(request)).problems().get(request).message())
        .contains("无法唯一匹配");
  }

  @Test
  void noCodeUsesTheActualBomSpecificationWithoutReadingOrganizationMasterModels() throws Exception {
    String code = code();
    material(code, "COMMERCIAL", "商用型号", "");
    material(code, "PLATE", "板换型号", "");
    db.importFile("2026-10", fixedRows(fixed("", "商用型号", "", "10"), fixed("", "板换型号", "", "20")));
    assertThat(selected(specification("", "商用型号", "210", "只")).fact().price()).isEqualByComparingTo("10");
    assertThat(selected(specification("", "板换型号", "220", "只")).fact().price()).isEqualByComparingTo("20");
  }

  @Test
  void adoptedInquiryStaysStableAcrossSourceImportAndUnadoptedMaterialGetsTheNewPrice()
      throws Exception {
    String code = code();
    db.importFile("2026-10", fixedRows(fixed(code, "", "", "10")));
    var request = request(code, "210", "只");
    var initial =
        prices.resolveCandidates(context(), Set.of(request)).values().get(request).selection();
    var receipt =
        prices.adopt(
            context(),
            new MonthlyAdoptionPlan(
                Map.of(request, initial),
                List.of(new Use("root", request, BigDecimal.ONE)),
                Set.of()));
    var adopted = receipt.plan().selections().get(request);
    db.importFile("2026-10", fixedRows(fixed(code, "", "", "20")));
    assertThat(
            prices.resolveCandidates(context(), Set.of(request)).values().get(request).selection())
        .isEqualTo(adopted);
    assertThat(selected(request).fact().price()).isEqualByComparingTo("20");
    assertThat(
            prices
                .readByIds("COMMERCIAL", Set.of(adopted.monthlyPriceId()))
                .get(adopted.monthlyPriceId())
                .fact()
                .price())
        .isEqualByComparingTo("10");
  }

  @Test
  void linkedInquiryForPlateUsesCommercialBaseAndSavedFormulaSurvivesLaterPublication()
      throws Exception {
    String code = code();
    material(code, "PLATE", "SBV-B10-001300(Q259A)", "");
    db.importFile("2026-10", InquiryWorkbookParserTest.original());
    var request = specification("", "SBV-B10-001300(Q259A)", "220", "件");
    var selected = selected(request);
    assertThat(selected.fact().evidence().json()).contains("CU_90000", "financeMonth");
    var stored = new Selection(999L, selected.applicability(), selected.fact());
    assertThat(new MonthlyMetalExposure(codec).read(Map.of(request, stored)))
        .containsEntry(999L, true);
    var projection = new MonthlyPriceScenarios(codec, null, null, null, null, null, null, null);
    var first = projection.calculate(Map.of(request, stored), new BigDecimal("100000")).get(999L);
    assertThat(first.financialPrice()).isEqualByComparingTo(selected.fact().price());
    assertThat(first.customerPrice()).isGreaterThan(first.financialPrice());
    db.publishFinance("2026-10", "CU_100000");
    assertThat(projection.calculate(Map.of(request, stored), new BigDecimal("100000")).get(999L))
        .isEqualTo(first);
    db.publishFinance("2026-10", "CU_90000");
  }
}
