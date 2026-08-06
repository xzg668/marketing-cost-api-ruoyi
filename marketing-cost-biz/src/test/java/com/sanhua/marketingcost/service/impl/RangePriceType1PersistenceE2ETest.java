package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@DisplayName("RPI1-12 区间价格类型1真实MySQL持久化端到端")
class RangePriceType1PersistenceE2ETest extends BomMapperTestBase {

  private static final String REAL_FILE_SHA256 =
      "a3f44d77e904724de2479924f76b8ec9cf0c8ed8ea1c129b8328b2ab25cc80bf";
  private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2025, 11, 1);
  private static final LocalDate EFFECTIVE_TO = LocalDate.of(2025, 11, 30);

  private static final String[][] DEFINITIONS = {
      {"MAT-001", "SPEC-001", "TEST-A", "测试供应商甲有限公司"},
      {"MAT-002", "SPEC-002", "TEST-A", "测试供应商甲有限公司"},
      {"MAT-003", "SPEC-003-A", "TEST-A", "测试供应商甲有限公司"},
      {"MAT-004", "SPEC-004-A", "TEST-A", "测试供应商甲有限公司"},
      {"MAT-001", "SPEC-001", "TEST-B", "测试供应商乙有限公司"},
      {"MAT-002", "SPEC-002", "TEST-B", "测试供应商乙有限公司"},
      {"MAT-005", "SPEC-005-B", "TEST-B", "测试供应商乙有限公司"},
      {"MAT-006", "SPEC-006-B", "TEST-B", "测试供应商乙有限公司"}
  };

  @Autowired
  private PriceRangeItemService service;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void prepareRangePriceSchema(DynamicPropertyRegistry ignored) {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS lp_price_range_item");
      statement.execute("DROP TABLE IF EXISTS lp_price_range_factor_rule");
      statement.execute(
          "CREATE TABLE lp_price_range_factor_rule ("
              + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
              + "business_unit_type VARCHAR(20) NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "material_name VARCHAR(128) NULL,"
              + "spec_model VARCHAR(128) NULL,"
              + "factor_code VARCHAR(32) NOT NULL,"
              + "factor_name VARCHAR(64) NULL,"
              + "factor_unit VARCHAR(32) NULL,"
              + "price_unit VARCHAR(32) NULL,"
              + "version_no INT NOT NULL DEFAULT 1,"
              + "import_batch_no VARCHAR(64) NOT NULL,"
              + "source_file VARCHAR(255) NULL,"
              + "source_sheet VARCHAR(128) NULL,"
              + "effective_from DATE NOT NULL,"
              + "effective_to DATE NULL,"
              + "current_flag TINYINT NOT NULL DEFAULT 1,"
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "CONSTRAINT chk_rpi1_rule_material "
              + "CHECK (material_code <> 'ROLLBACK-FAIL'),"
              + "KEY idx_factor_rule_current "
              + "(business_unit_type,material_code,current_flag)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_price_range_item ("
              + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
              + "org_code VARCHAR(64) NULL,"
              + "source_name VARCHAR(128) NULL,"
              + "supplier_name VARCHAR(255) NULL,"
              + "supplier_code VARCHAR(64) NULL,"
              + "purchase_class VARCHAR(64) NULL,"
              + "material_name VARCHAR(128) NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "spec_model VARCHAR(128) NULL,"
              + "unit VARCHAR(32) NULL,"
              + "formula_expr TEXT NULL,"
              + "blank_weight DECIMAL(20,8) NULL,"
              + "net_weight DECIMAL(20,8) NULL,"
              + "process_fee DECIMAL(20,8) NULL,"
              + "agent_fee DECIMAL(20,8) NULL,"
              + "range_low DECIMAL(20,8) NOT NULL,"
              + "range_high DECIMAL(20,8) NOT NULL,"
              + "range_basis VARCHAR(16) NOT NULL DEFAULT 'QTY',"
              + "factor_rule_id BIGINT NULL,"
              + "factor_code VARCHAR(32) NULL,"
              + "import_batch_no VARCHAR(64) NULL,"
              + "current_flag TINYINT NOT NULL DEFAULT 1,"
              + "price_excl_tax DECIMAL(20,8) NULL,"
              + "price_incl_tax DECIMAL(20,8) NULL,"
              + "tax_included TINYINT NOT NULL DEFAULT 1,"
              + "effective_from DATE NOT NULL,"
              + "effective_to DATE NULL,"
              + "order_type VARCHAR(64) NULL,"
              + "quota DECIMAL(20,8) NULL,"
              + "business_unit_type VARCHAR(20) NULL,"
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "KEY idx_range_factor_rule (factor_rule_id),"
              + "KEY idx_range_current "
              + "(business_unit_type,material_code,factor_code,current_flag)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Exception exception) {
      throw new IllegalStateException("RPI1-12隔离表初始化失败", exception);
    }
  }

  @BeforeEach
  void clearRangeRows() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM lp_price_range_item");
      statement.execute("DELETE FROM lp_price_range_factor_rule");
    }
  }

  @Test
  @DisplayName("脱敏8行80条实际写入规则表和明细表并可准确查询")
  void sanitizedEightyRowsPersistIntoIsolatedMysql() throws Exception {
    List<PriceRangeItem> imported = service.importItems(sanitizedRequest("RPI1-12-SANITIZED"));

    assertThat(imported).hasSize(80);
    assertPersistedEightyRows("MAT-001", "MAT-002", "TEST-A", "TEST-B");
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item a "
            + "JOIN lp_price_range_item b ON a.id < b.id "
            + "AND a.material_code=b.material_code "
            + "AND a.supplier_code=b.supplier_code "
            + "AND a.current_flag=1 AND b.current_flag=1 "
            + "AND a.range_low <= b.range_high AND b.range_low <= a.range_high"))
        .isZero();
    assertThat(queryInt(
        "SELECT COUNT(DISTINCT supplier_code) FROM lp_price_range_item "
            + "WHERE material_code='MAT-001' AND range_low=57001 "
            + "AND range_high=60000 AND current_flag=1"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("完全相同请求重复导入保持6条当前规则和80条当前明细")
  void identicalReimportIsIdempotent() throws Exception {
    PriceRangeItemImportRequest request = sanitizedRequest("RPI1-12-IDEMPOTENT");

    assertThat(service.importItems(request)).hasSize(80);
    assertThat(service.importItems(request)).hasSize(80);

    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_factor_rule")).isEqualTo(6);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item")).isEqualTo(80);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_factor_rule WHERE current_flag=1"))
        .isEqualTo(6);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1"))
        .isEqualTo(80);
  }

  @Test
  @DisplayName("价格变化生成历史版本且仍只有6条当前规则和80条当前明细")
  void changedPriceCreatesHistoryWithoutDuplicateCurrentRows() throws Exception {
    service.importItems(sanitizedRequest("RPI1-12-V1"));
    PriceRangeItemImportRequest changed = sanitizedRequest("RPI1-12-V2");
    changed.getRows().get(0).setPriceExclTax(new BigDecimal("9.87654321"));

    assertThat(service.importItems(changed)).hasSize(80);

    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_factor_rule")).isEqualTo(7);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item")).isEqualTo(100);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_factor_rule WHERE current_flag=1"))
        .isEqualTo(6);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1"))
        .isEqualTo(80);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_factor_rule "
            + "WHERE material_code='MAT-001' AND current_flag=0 "
            + "AND effective_to='2025-11-01'"))
        .isEqualTo(1);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item "
            + "WHERE material_code='MAT-001' AND current_flag=0 "
            + "AND effective_from='2025-11-01' AND effective_to='2025-11-30'"))
        .isEqualTo(20);
  }

  @Test
  @DisplayName("第二个物料数据库失败时第一个物料规则和明细整批回滚")
  void databaseFailureRollsBackWholeBatch() throws Exception {
    PriceRangeItemImportRequest request = factorRequest("RPI1-12-ROLLBACK");
    request.setRows(List.of(
        row("ROLLBACK-OK", "SPEC-OK", "TEST-A", "测试供应商甲有限公司", 0, 0),
        row("ROLLBACK-FAIL", "SPEC-FAIL", "TEST-B", "测试供应商乙有限公司", 0, 1)));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(DataAccessException.class);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_factor_rule")).isZero();
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item")).isZero();
  }

  @Test
  @DisplayName("前端真实Excel临时请求实际写入6条规则和80条明细")
  void realExcelPayloadPersistsIntoIsolatedMysql() throws Exception {
    String payloadFile = System.getProperty("rpi1.type1.payload");
    assumeTrue(payloadFile != null && Files.isRegularFile(Path.of(payloadFile)),
        "未提供-rpi1.type1.payload，真实文件仍由前端只读E2E覆盖");

    JsonNode wrapper = objectMapper.readTree(Path.of(payloadFile).toFile());
    assertThat(wrapper.path("sourceSha256").asText()).isEqualTo(REAL_FILE_SHA256);
    assertThat(wrapper.path("sheetNames")).hasSize(3);
    assertThat(wrapper.path("matchedRowCount").asInt()).isEqualTo(8);
    assertThat(wrapper.path("expandedRowCount").asInt()).isEqualTo(80);
    PriceRangeItemImportRequest request =
        objectMapper.treeToValue(wrapper.path("request"), PriceRangeItemImportRequest.class);
    request.setBusinessUnitType("COMMERCIAL");

    assertThat(service.importItems(request)).hasSize(80);
    assertPersistedEightyRows("201503873", "201503874", "S000841", "S001289");
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item "
            + "WHERE supplier_code='S000841' "
            + "AND supplier_name='公主岭市远达实业有限公司'"))
        .isEqualTo(40);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item "
            + "WHERE supplier_code='S001289' "
            + "AND supplier_name='吉林省合信汽配有限公司'"))
        .isEqualTo(40);
    assertThat(queryDecimal(
        "SELECT price_excl_tax FROM lp_price_range_item "
            + "WHERE material_code='201503873' AND supplier_code='S000841' "
            + "AND range_low=57001 AND range_high=60000"))
        .isEqualByComparingTo("0.99469027");
    assertThat(queryDecimal(
        "SELECT price_incl_tax FROM lp_price_range_item "
            + "WHERE material_code='201503873' AND supplier_code='S000841' "
            + "AND range_low=57001 AND range_high=60000"))
        .isEqualByComparingTo("1.12400000");
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_factor_rule "
            + "WHERE source_file='采购价表二次开发导入模板251115区间价格导入类型1.xls' "
            + "AND source_sheet='Sheet1' AND factor_code='CU'"))
        .isEqualTo(6);
  }

  private void assertPersistedEightyRows(
      String sharedMaterialA,
      String sharedMaterialB,
      String supplierA,
      String supplierB) throws Exception {
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_factor_rule")).isEqualTo(6);
    assertThat(queryInt("SELECT COUNT(*) FROM lp_price_range_item")).isEqualTo(80);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_factor_rule "
            + "WHERE current_flag=1 AND factor_code='CU' "
            + "AND effective_from='2025-11-01' AND effective_to IS NULL"))
        .isEqualTo(6);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item "
            + "WHERE current_flag=1 AND range_basis='FACTOR' AND factor_code='CU' "
            + "AND effective_from='2025-11-01' AND effective_to='2025-11-30'"))
        .isEqualTo(80);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1 "
            + "AND range_low=57001 AND range_high=60000"))
        .isEqualTo(8);
    assertThat(queryInt(
        "SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1 "
            + "AND range_low=84001 AND range_high=87000"))
        .isEqualTo(8);
    for (String materialCode : List.of(sharedMaterialA, sharedMaterialB)) {
      assertThat(queryInt(
          "SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1 "
              + "AND material_code='" + materialCode + "' "
              + "AND supplier_code='" + supplierA + "'"))
          .isEqualTo(10);
      assertThat(queryInt(
          "SELECT COUNT(*) FROM lp_price_range_item WHERE current_flag=1 "
              + "AND material_code='" + materialCode + "' "
              + "AND supplier_code='" + supplierB + "'"))
          .isEqualTo(10);
    }
  }

  private PriceRangeItemImportRequest sanitizedRequest(String batchNo) {
    PriceRangeItemImportRequest request = factorRequest(batchNo);
    List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows = new ArrayList<>();
    for (int definitionIndex = 0; definitionIndex < DEFINITIONS.length; definitionIndex += 1) {
      String[] definition = DEFINITIONS[definitionIndex];
      for (int intervalIndex = 0; intervalIndex < 10; intervalIndex += 1) {
        rows.add(row(
            definition[0],
            definition[1],
            definition[2],
            definition[3],
            definitionIndex,
            intervalIndex));
      }
    }
    request.setRows(rows);
    return request;
  }

  private PriceRangeItemImportRequest factorRequest(String batchNo) {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRangeBasis("FACTOR");
    request.setFactorCode("CU");
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("只");
    request.setSourceFile("区间价格类型1-脱敏夹具.xlsx");
    request.setSourceSheet("Sheet1");
    request.setImportBatchNo(batchNo);
    return request;
  }

  private PriceRangeItemImportRequest.PriceRangeItemImportRow row(
      String materialCode,
      String specModel,
      String supplierCode,
      String supplierName,
      int definitionIndex,
      int intervalIndex) {
    int rangeLow = 57001 + intervalIndex * 3000;
    int rangeHigh = 60000 + intervalIndex * 3000;
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setRangeBasis("FACTOR");
    row.setFactorCode("CU");
    row.setOrgCode("TEST-ORG");
    row.setSourceName("脱敏夹具");
    row.setSupplierName(supplierName);
    row.setSupplierCode(supplierCode);
    row.setPurchaseClass("部品固定");
    row.setMaterialName("测试气门芯");
    row.setMaterialCode(materialCode);
    row.setSpecModel(specModel);
    row.setUnit("只");
    row.setRangeLow(BigDecimal.valueOf(rangeLow));
    row.setRangeHigh(BigDecimal.valueOf(rangeHigh));
    row.setPriceExclTax(new BigDecimal("0.90000000")
        .add(BigDecimal.valueOf(definitionIndex, 1))
        .add(BigDecimal.valueOf(intervalIndex, 2)));
    row.setPriceInclTax(row.getPriceExclTax().multiply(new BigDecimal("1.13")));
    row.setTaxIncluded(false);
    row.setEffectiveFrom(EFFECTIVE_FROM);
    row.setEffectiveTo(EFFECTIVE_TO);
    row.setOrderType("VMI采购");
    return row;
  }

  private int queryInt(String sql) throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getInt(1);
    }
  }

  private BigDecimal queryDecimal(String sql) throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getBigDecimal(1);
    }
  }
}
