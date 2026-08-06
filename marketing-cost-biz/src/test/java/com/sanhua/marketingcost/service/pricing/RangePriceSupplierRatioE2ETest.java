package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioCandidate;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.service.impl.CostRunPartItemServiceImpl;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@DisplayName("RPI1-13 多供应商供货比例区间取价真实MySQL端到端")
class RangePriceSupplierRatioE2ETest extends BomMapperTestBase {

  private static final String MATERIAL_A = "201503873";
  private static final String MATERIAL_B = "201503874";
  private static final String SUPPLIER_A_CODE = "S000841";
  private static final String SUPPLIER_A_NAME = "公主岭市远达实业有限公司";
  private static final String SUPPLIER_B_CODE = "S001289";
  private static final String SUPPLIER_B_NAME = "吉林省合信汽配有限公司";
  private static final LocalDate PRICE_DATE = LocalDate.of(2025, 11, 15);
  private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2025, 11, 1);
  private static final LocalDate EFFECTIVE_TO = LocalDate.of(2025, 11, 30);

  @Autowired private PriceRangeItemService priceRangeItemService;
  @Autowired private PriceRangeItemMapper priceRangeItemMapper;
  @Autowired private PriceRangeFactorRuleMapper factorRuleMapper;
  @Autowired private SupplierPreferredPriceSelector supplierSelector;
  @Autowired private SupplierSupplyRatioResolveService ratioResolveService;

  @DynamicPropertySource
  static void prepareIsolatedSchema(DynamicPropertyRegistry ignored) {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS lp_price_range_item");
      statement.execute("DROP TABLE IF EXISTS lp_price_range_factor_rule");
      statement.execute("DROP TABLE IF EXISTS lp_supplier_supply_ratio");
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
      statement.execute(
          "CREATE TABLE lp_supplier_supply_ratio ("
              + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
              + "business_unit_type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL',"
              + "material_code VARCHAR(64) NOT NULL,"
              + "material_name VARCHAR(180) NOT NULL,"
              + "spec_model VARCHAR(255) NOT NULL DEFAULT '',"
              + "unit VARCHAR(32) NULL,"
              + "material_shape VARCHAR(64) NULL,"
              + "supplier_name VARCHAR(180) NOT NULL,"
              + "supplier_code VARCHAR(64) NULL,"
              + "supply_ratio DECIMAL(18,6) NOT NULL DEFAULT 0,"
              + "effective_from DATE NULL,"
              + "effective_to DATE NULL,"
              + "source_type VARCHAR(32) NOT NULL DEFAULT 'EXCEL',"
              + "source_batch_no VARCHAR(64) NULL,"
              + "import_file_name VARCHAR(255) NULL,"
              + "imported_by VARCHAR(64) NULL,"
              + "imported_at DATETIME NULL,"
              + "created_by VARCHAR(64) NULL,"
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "updated_by VARCHAR(64) NULL,"
              + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "deleted TINYINT NOT NULL DEFAULT 0,"
              + "UNIQUE KEY uk_supplier_ratio_biz "
              + "(business_unit_type,material_code,supplier_name,deleted),"
              + "KEY idx_supplier_ratio_material "
              + "(business_unit_type,material_code,material_name,spec_model,supply_ratio)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Exception exception) {
      throw new IllegalStateException("RPI1-13隔离表初始化失败", exception);
    }
  }

  @BeforeEach
  void clearRows() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM lp_price_range_item");
      statement.execute("DELETE FROM lp_price_range_factor_rule");
      statement.execute("DELETE FROM lp_supplier_supply_ratio");
    }
  }

  @Test
  @DisplayName("真实共用料号随供货比例在S000841和S001289之间切换并按代码匹配")
  void realSharedMaterialsSwitchSupplierByHigherRatioAndCode() throws Exception {
    seedMaterial(MATERIAL_A);
    seedMaterial(MATERIAL_B);
    replaceRatios(MATERIAL_A, "0.700000", "0.300000");
    replaceRatios(MATERIAL_B, "0.700000", "0.300000");

    assertThat(queryRatioCount(MATERIAL_A)).isEqualTo(2);
    assertThat(ratioResolveService.resolve(
            "COMMERCIAL", MATERIAL_A, "测试气门芯", "SPEC-" + MATERIAL_A, PRICE_DATE)
        .isMatched()).isTrue();
    assertThat(ratioResolveService.resolveAmongSuppliers(
            "COMMERCIAL",
            MATERIAL_A,
            "测试气门芯",
            "SPEC-" + MATERIAL_A,
            PRICE_DATE,
            List.of(
                new SupplierSupplyRatioCandidate(SUPPLIER_A_NAME, SUPPLIER_A_CODE),
                new SupplierSupplyRatioCandidate(SUPPLIER_B_NAME, SUPPLIER_B_CODE)))
        .isMatched()).isTrue();

    for (String material : List.of(MATERIAL_A, MATERIAL_B)) {
      PriceResolveResult result = resolve(material, "58500");
      assertSelected(result, material, SUPPLIER_A_CODE, "0.700000", "供应商代码", false);
    }

    replaceRatios(MATERIAL_A, "0.200000", "0.800000");
    replaceRatios(MATERIAL_B, "0.200000", "0.800000");
    for (String material : List.of(MATERIAL_A, MATERIAL_B)) {
      PriceResolveResult result = resolve(material, "58500");
      assertSelected(result, material, SUPPLIER_B_CODE, "0.800000", "供应商代码", false);
    }
  }

  @Test
  @DisplayName("供货比例代码为空时按完整名称匹配")
  void blankRatioCodeFallsBackToFullSupplierName() throws Exception {
    seedMaterial(MATERIAL_A);
    insertRatio(
        MATERIAL_A,
        SUPPLIER_A_NAME,
        SUPPLIER_A_CODE,
        "0.200000",
        LocalDateTime.of(2025, 11, 1, 8, 0));
    insertRatio(
        MATERIAL_A,
        SUPPLIER_B_NAME,
        null,
        "0.800000",
        LocalDateTime.of(2025, 11, 1, 8, 1));

    PriceResolveResult result = resolve(MATERIAL_A, "58500");

    assertThat(result.unitPrice()).isEqualByComparingTo("2.00000000");
    assertThat(querySupplierCode(result.resultRefId())).isEqualTo(SUPPLIER_B_CODE);
    assertThat(result.remark()).contains(
        "物料代码=" + MATERIAL_A,
        "候选供应商数量=2",
        "主供应商名称=" + SUPPLIER_B_NAME,
        "主供应商代码=；供货比例=0.8",
        "供应商匹配方式=供应商名称兜底",
        "最终价格行ID=" + result.resultRefId(),
        "最终不含税单价=2",
        "是否兜底=否");
  }

  @Test
  @DisplayName("代码明确不同时不按同名误匹配且主供无价格时留下兜底原因")
  void explicitCodeMismatchNeverFallsBackToSameName() throws Exception {
    seedMaterial(MATERIAL_A);
    insertRatio(
        MATERIAL_A,
        SUPPLIER_A_NAME,
        "OTHER-CODE",
        "1.000000",
        LocalDateTime.of(2025, 11, 1, 8, 0));

    PriceResolveResult result = resolve(MATERIAL_A, "58500");

    assertThat(querySupplierCode(result.resultRefId())).isEqualTo(SUPPLIER_B_CODE);
    assertThat(result.remark())
        .contains(
            "主供应商名称=" + SUPPLIER_A_NAME,
            "主供应商代码=OTHER-CODE",
            "供应商匹配方式=默认排序兜底",
            "是否兜底=是",
            "兜底原因=主供应商无价格记录")
        .doesNotContain("供应商匹配方式=供应商名称兜底");
  }

  @Test
  @DisplayName("比例相同先按更新时间再按ID选择")
  void equalRatioUsesUpdatedAtThenId() throws Exception {
    seedMaterial(MATERIAL_A);
    insertRatio(
        MATERIAL_A,
        SUPPLIER_A_NAME,
        SUPPLIER_A_CODE,
        "0.500000",
        LocalDateTime.of(2025, 11, 2, 8, 0));
    insertRatio(
        MATERIAL_A,
        SUPPLIER_B_NAME,
        SUPPLIER_B_CODE,
        "0.500000",
        LocalDateTime.of(2025, 11, 1, 8, 0));

    assertThat(querySupplierCode(resolve(MATERIAL_A, "58500").resultRefId()))
        .isEqualTo(SUPPLIER_A_CODE);

    clearRatios();
    LocalDateTime sameTime = LocalDateTime.of(2025, 11, 3, 8, 0);
    insertRatio(MATERIAL_A, SUPPLIER_A_NAME, SUPPLIER_A_CODE, "0.500000", sameTime);
    insertRatio(MATERIAL_A, SUPPLIER_B_NAME, SUPPLIER_B_CODE, "0.500000", sameTime);

    PriceResolveResult idTieResult = resolve(MATERIAL_A, "58500");
    assertSelected(
        idTieResult, MATERIAL_A, SUPPLIER_B_CODE, "0.500000", "供应商代码", false);
  }

  @Test
  @DisplayName("未维护供货比例时按价格ID倒序兜底并记录原因")
  void missingRatioUsesDeterministicFallbackWithReason() throws Exception {
    seedMaterial(MATERIAL_A);

    PriceResolveResult first = resolve(MATERIAL_A, "58500");
    PriceResolveResult repeated = resolve(MATERIAL_A, "58500");

    assertThat(querySupplierCode(first.resultRefId())).isEqualTo(SUPPLIER_B_CODE);
    assertThat(first).isEqualTo(repeated);
    assertThat(first.remark()).contains(
        "供应商匹配方式=默认排序兜底",
        "是否兜底=是",
        "兜底原因=未维护主供应商供货比例");
  }

  @Test
  @DisplayName("十个区间二十个闭区间边界全部命中且区间外明确缺价")
  void allClosedIntervalBoundariesResolveAndOutsideReturnsMiss() throws Exception {
    seedMaterial(MATERIAL_A);
    replaceRatios(MATERIAL_A, "0.800000", "0.200000");

    for (int interval = 0; interval < 10; interval += 1) {
      int low = 57001 + interval * 3000;
      int high = 60000 + interval * 3000;
      BigDecimal expectedPrice = supplierPrice(SUPPLIER_A_CODE, interval);
      for (int boundary : List.of(low, high)) {
        PriceResolveResult result = resolve(MATERIAL_A, String.valueOf(boundary));
        assertThat(result.unitPrice()).isEqualByComparingTo(expectedPrice);
        assertThat(result.remark()).contains(
            "报价单行情值=" + boundary,
            "命中区间=" + low + "-" + high,
            "主供应商代码=" + SUPPLIER_A_CODE,
            "最终价格行ID=" + result.resultRefId());
      }
    }

    for (String outside : List.of("57000", "87001")) {
      PriceResolveResult miss = resolve(MATERIAL_A, outside);
      assertThat(miss.unitPrice()).isNull();
      assertThat(miss.resultRefId()).isNull();
      assertThat(miss.remark()).contains(
          "行情因素区间价未命中当前区间",
          "material=" + MATERIAL_A,
          "factor=CU",
          "value=" + outside);
    }
  }

  @Test
  @DisplayName("成本服务使用不含税价乘BOM用量且切换主供不影响其他物料")
  void costRunUsesExclTaxAndOnlyChangesSelectedMaterialAmount() throws Exception {
    seedMaterial(MATERIAL_A);
    replaceRatios(MATERIAL_A, "0.800000", "0.200000");
    RangePriceResolver rangeResolver = resolver("58500");
    CostRunPartItemServiceImpl costService = costService(rangeResolver);

    List<CostRunPartItemDto> supplierAItems = runCost(costService);
    CostRunPartItemDto supplierATarget = supplierAItems.get(0);
    CostRunPartItemDto supplierAOther = supplierAItems.get(1);
    BigDecimal supplierATotal = total(supplierAItems);
    assertThat(supplierATarget.getUnitPrice()).isEqualByComparingTo("1.00000000");
    assertThat(supplierATarget.getAmount()).isEqualByComparingTo("3.500000000");
    assertThat(supplierAOther.getUnitPrice()).isEqualByComparingTo("10.00");
    assertThat(supplierAOther.getAmount()).isEqualByComparingTo("20.00");
    assertThat(supplierATotal).isEqualByComparingTo("23.500000000");

    replaceRatios(MATERIAL_A, "0.200000", "0.800000");
    List<CostRunPartItemDto> supplierBItems = runCost(costService);
    CostRunPartItemDto supplierBTarget = supplierBItems.get(0);
    CostRunPartItemDto supplierBOther = supplierBItems.get(1);
    BigDecimal supplierBTotal = total(supplierBItems);
    assertThat(supplierBTarget.getUnitPrice()).isEqualByComparingTo("2.00000000");
    assertThat(supplierBTarget.getAmount()).isEqualByComparingTo("7.000000000");
    assertThat(supplierBOther.getUnitPrice()).isEqualByComparingTo(supplierAOther.getUnitPrice());
    assertThat(supplierBOther.getAmount()).isEqualByComparingTo(supplierAOther.getAmount());
    assertThat(supplierBTotal.subtract(supplierATotal))
        .isEqualByComparingTo(supplierBTarget.getAmount().subtract(supplierATarget.getAmount()));
    assertThat(supplierBTarget.getRemark()).contains(
        "主供应商代码=" + SUPPLIER_B_CODE,
        "供货比例=0.8",
        "命中区间=57001-60000",
        "最终价格行ID=",
        "最终不含税单价=2");
    assertThat(runCost(costService)).usingRecursiveComparison().isEqualTo(supplierBItems);
  }

  private void seedMaterial(String materialCode) {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRangeBasis("FACTOR");
    request.setFactorCode("CU");
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("只");
    request.setSourceFile("RPI1-13-多供应商脱敏夹具.xlsx");
    request.setSourceSheet("Sheet1");
    request.setImportBatchNo("RPI1-13-" + materialCode);
    List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows = new ArrayList<>();
    for (String supplierCode : List.of(SUPPLIER_A_CODE, SUPPLIER_B_CODE)) {
      for (int interval = 0; interval < 10; interval += 1) {
        rows.add(priceRow(materialCode, supplierCode, interval));
      }
    }
    request.setRows(rows);
    assertThat(priceRangeItemService.importItems(request)).hasSize(20);
  }

  private PriceRangeItemImportRequest.PriceRangeItemImportRow priceRow(
      String materialCode, String supplierCode, int interval) {
    int rangeLow = 57001 + interval * 3000;
    int rangeHigh = 60000 + interval * 3000;
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setRangeBasis("FACTOR");
    row.setFactorCode("CU");
    row.setOrgCode("TEST-ORG");
    row.setSourceName("RPI1-13脱敏夹具");
    row.setSupplierName(
        SUPPLIER_A_CODE.equals(supplierCode) ? SUPPLIER_A_NAME : SUPPLIER_B_NAME);
    row.setSupplierCode(supplierCode);
    row.setPurchaseClass("部品固定");
    row.setMaterialName("测试气门芯");
    row.setMaterialCode(materialCode);
    row.setSpecModel("SPEC-" + materialCode);
    row.setUnit("只");
    row.setRangeLow(BigDecimal.valueOf(rangeLow));
    row.setRangeHigh(BigDecimal.valueOf(rangeHigh));
    row.setPriceExclTax(supplierPrice(supplierCode, interval));
    row.setPriceInclTax(
        new BigDecimal(SUPPLIER_A_CODE.equals(supplierCode) ? "101" : "202")
            .add(BigDecimal.valueOf(interval)));
    row.setTaxIncluded(false);
    row.setEffectiveFrom(EFFECTIVE_FROM);
    row.setEffectiveTo(EFFECTIVE_TO);
    row.setOrderType("VMI采购");
    return row;
  }

  private BigDecimal supplierPrice(String supplierCode, int interval) {
    BigDecimal base = SUPPLIER_A_CODE.equals(supplierCode)
        ? new BigDecimal("1.00000000")
        : new BigDecimal("2.00000000");
    return base.add(BigDecimal.valueOf(interval, 2));
  }

  private PriceResolveResult resolve(String materialCode, String copperPrice) {
    return resolver(copperPrice).resolve(
        "OA-RPI1-13", part(materialCode, "1"), rangeRoute(materialCode), quoteContext());
  }

  private RangePriceResolver resolver(String copperPrice) {
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    OaForm oaForm = new OaForm();
    oaForm.setOaNo("OA-RPI1-13");
    oaForm.setCopperPrice(new BigDecimal(copperPrice));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(oaForm);
    return new RangePriceResolver(
        priceRangeItemMapper, factorRuleMapper, oaFormMapper, supplierSelector);
  }

  private void replaceRatios(String materialCode, String supplierARatio, String supplierBRatio)
      throws Exception {
    clearRatios(materialCode);
    insertRatio(
        materialCode,
        SUPPLIER_A_NAME,
        SUPPLIER_A_CODE,
        supplierARatio,
        LocalDateTime.of(2025, 11, 1, 8, 0));
    insertRatio(
        materialCode,
        SUPPLIER_B_NAME,
        SUPPLIER_B_CODE,
        supplierBRatio,
        LocalDateTime.of(2025, 11, 1, 8, 1));
  }

  private void insertRatio(
      String materialCode,
      String supplierName,
      String supplierCode,
      String ratio,
      LocalDateTime updatedAt)
      throws Exception {
    String sql =
        "INSERT INTO lp_supplier_supply_ratio "
            + "(business_unit_type,material_code,material_name,spec_model,supplier_name,"
            + "supplier_code,supply_ratio,effective_from,effective_to,source_type,"
            + "source_batch_no,updated_at,deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)";
    try (Connection connection = openConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "COMMERCIAL");
      statement.setString(2, materialCode);
      statement.setString(3, "测试气门芯");
      statement.setString(4, "SPEC-" + materialCode);
      statement.setString(5, supplierName);
      statement.setString(6, supplierCode);
      statement.setBigDecimal(7, new BigDecimal(ratio));
      statement.setObject(8, EFFECTIVE_FROM);
      statement.setObject(9, EFFECTIVE_TO);
      statement.setString(10, "EXCEL");
      statement.setString(11, "RPI1-13-RATIO");
      statement.setObject(12, updatedAt);
      statement.executeUpdate();
    }
  }

  private void clearRatios(String materialCode) throws Exception {
    try (Connection connection = openConnection();
        PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM lp_supplier_supply_ratio WHERE material_code=?")) {
      statement.setString(1, materialCode);
      statement.executeUpdate();
    }
  }

  private void clearRatios() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM lp_supplier_supply_ratio");
    }
  }

  private void assertSelected(
      PriceResolveResult result,
      String materialCode,
      String supplierCode,
      String ratio,
      String matchMode,
      boolean fallback)
      throws Exception {
    assertThat(result.unitPrice()).isNotNull();
    assertThat(result.priceSource()).isEqualTo("区间价");
    assertThat(querySupplierCode(result.resultRefId())).isEqualTo(supplierCode);
    assertThat(result.remark()).contains(
        "物料代码=" + materialCode,
        "候选供应商数量=2",
        "主供应商代码=" + supplierCode,
        "供货比例=" + new BigDecimal(ratio).stripTrailingZeros().toPlainString(),
        "供应商匹配方式=" + matchMode,
        "最终价格行ID=" + result.resultRefId(),
        "最终不含税单价=" + result.unitPrice().stripTrailingZeros().toPlainString(),
        "是否兜底=" + (fallback ? "是" : "否"));
  }

  private String querySupplierCode(Long priceRowId) throws Exception {
    try (Connection connection = openConnection();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT supplier_code FROM lp_price_range_item WHERE id=?")) {
      statement.setLong(1, priceRowId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  private int queryRatioCount(String materialCode) throws Exception {
    try (Connection connection = openConnection();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM lp_supplier_supply_ratio "
                + "WHERE business_unit_type='COMMERCIAL' AND material_code=? "
                + "AND effective_from<='2025-11-15' AND effective_to>='2025-11-15' "
                + "AND deleted=0")) {
      statement.setString(1, materialCode);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getInt(1);
      }
    }
  }

  private CostRunPartItemServiceImpl costService(RangePriceResolver rangeResolver) {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialPriceRouterService router = mock(MaterialPriceRouterService.class);
    when(partMapper.selectBaseByQuoteScope(
            eq("OA-RPI1-13"), eq(1L), eq("PRODUCT-RPI1-13"), eq("2025-11")))
        .thenAnswer(ignored -> costInputItems());
    when(router.listCandidates(eq(MATERIAL_A), eq("2025-11"), eq(PRICE_DATE)))
        .thenReturn(List.of(rangeRoute(MATERIAL_A)));
    when(router.listCandidates(eq("OTHER-MATERIAL"), eq("2025-11"), eq(PRICE_DATE)))
        .thenReturn(List.of(fixedRoute("OTHER-MATERIAL")));
    PriceResolver fixedResolver = new PriceResolver() {
      @Override
      public PriceTypeEnum priceType() {
        return PriceTypeEnum.FIXED;
      }

      @Override
      public PriceResolveResult resolve(
          String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        return PriceResolveResult.hit(new BigDecimal("10.00"), "固定采购价", 999L);
      }
    };
    return new CostRunPartItemServiceImpl(
        partMapper,
        router,
        mock(PackageComponentIdentifyService.class),
        mock(PackageComponentPriceService.class),
        mock(OaFormMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class),
        List.of(rangeResolver, fixedResolver));
  }

  private List<CostRunPartItemDto> runCost(CostRunPartItemServiceImpl costService) {
    return costService.listByOaNo(
        "OA-RPI1-13", PRICE_DATE, quoteContext(), false, ignored -> {});
  }

  private List<CostRunPartItemDto> costInputItems() {
    CostRunPartItemDto target = part(MATERIAL_A, "3.5");
    CostRunPartItemDto other = part("OTHER-MATERIAL", "2");
    return new ArrayList<>(List.of(target, other));
  }

  private BigDecimal total(List<CostRunPartItemDto> items) {
    return items.stream()
        .map(CostRunPartItemDto::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static CostRunPartItemDto part(String materialCode, String quantity) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo("OA-RPI1-13");
    item.setProductCode("PRODUCT-RPI1-13");
    item.setPartCode(materialCode);
    item.setPartName(materialCode);
    item.setPartQty(new BigDecimal(quantity));
    item.setShapeAttr("采购件");
    item.setPriceOrgCode("210");
    item.setMaterialOrganizationCode("COMMERCIAL");
    return item;
  }

  private static CostRunContext quoteContext() {
    return CostRunContext.quote(
        "OA-RPI1-13",
        1L,
        "PRODUCT-RPI1-13",
        null,
        null,
        "COMMERCIAL",
        "2025-11",
        PRICE_DATE.atTime(9, 0),
        "RPI1-13-OBJECT");
  }

  private static PriceTypeRoute rangeRoute(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.RANGE,
        1,
        EFFECTIVE_FROM,
        EFFECTIVE_TO,
        "manual",
        "区间价");
  }

  private static PriceTypeRoute fixedRoute(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.FIXED,
        1,
        EFFECTIVE_FROM,
        EFFECTIVE_TO,
        "manual",
        "固定采购价");
  }
}
