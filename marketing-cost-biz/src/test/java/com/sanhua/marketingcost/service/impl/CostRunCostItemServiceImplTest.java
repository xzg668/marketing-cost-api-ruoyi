package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.entity.ThreeExpenseDimensionMapping;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本汇总口径校准测试 (Task #9)。
 *
 * <p>本测试聚焦在新接入的产品属性系数查询逻辑——金标产品 1079900000536 是标准品，
 * coefficient = 1.0；未维护属性的兜底料号必须回落到 1（与 V11 DEFAULT 对齐）。
 * 整链路（含 calculateItems 12 个 mapper）的金标断言由 GoldenSampleRegressionTest 兜底。
 */
class CostRunCostItemServiceImplTest {

  @Test
  @DisplayName("T9 辅料 DIRECT 模式：金额直接取 unit_price，不乘上浮率")
  void auxDirectAmountUsesUnitPrice() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto direct = newAuxCost("0201", "辅助焊料类", "12.345678", "9.9900", "DIRECT");
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(direct));

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-1"));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getCostCode()).isEqualTo("AUX_0201");
    assertThat(items.get(0).getCostName()).isEqualTo("辅助焊料类");
    assertThat(items.get(0).getBaseAmount()).isEqualByComparingTo("12.345678");
    assertThat(items.get(0).getRate()).isNull();
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("12.345678");
  }

  @Test
  @DisplayName("T9 辅料 RATE 模式：保持 unit_price × float_rate 原行为")
  void auxRateAmountKeepsLegacyFormula() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto rate = newAuxCost("1004", "清洗费", "4.000000", "0.0400", "RATE");
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(rate));

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-1"));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getCostCode()).isEqualTo("AUX_1004");
    assertThat(items.get(0).getBaseAmount()).isEqualByComparingTo("4.000000");
    assertThat(items.get(0).getRate()).isEqualByComparingTo("0.0400");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("0.160000");
  }

  @Test
  @DisplayName("T9 辅料兼容：amount_calc_mode 为空时按历史 RATE 口径计算")
  void auxBlankModeFallsBackToRateFormula() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto legacy = newAuxCost("1001", "气体", "3.000000", "0.0100", null);
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(legacy));

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-1"));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getRate()).isEqualByComparingTo("0.0100");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("0.030000");
  }

  @Test
  @DisplayName("T9 CMS 公共生效辅料：正式核算只取公共生效来源并乘 1.05")
  void auxCmsEffectiveUsesEffectiveAmountAndDisplayOrder() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto cmsSecond = newAuxCost("0202", "表面处理类", "0.600000", "9.9900", "DIRECT");
    cmsSecond.setMaterialCode("P-1");
    cmsSecond.setSource("CMS_EFFECTIVE");
    cmsSecond.setDisplayOrder(20);
    AuxCostItemDto cmsFirst = newAuxCost("0201", "辅助焊料类", "0.400000", "9.9900", "DIRECT");
    cmsFirst.setMaterialCode("P-1");
    cmsFirst.setSource("CMS_EFFECTIVE");
    cmsFirst.setDisplayOrder(10);
    AuxCostItemDto oldCmsDerived = newAuxCost("0201", "旧CMS派生", "99.000000", "9.9900", "DIRECT");
    oldCmsDerived.setMaterialCode("P-1");
    oldCmsDerived.setSource("CMS");
    AuxCostItemDto manual = newAuxCost("1004", "清洗费", "4.000000", "0.0400", "RATE");
    manual.setMaterialCode("P-1");
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-1"), "COMMERCIAL"))
        .thenReturn(List.of(cmsSecond, cmsFirst));
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(oldCmsDerived, manual));

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-1"), 2026, "COMMERCIAL");

    assertThat(items).extracting(CostRunCostItemDto::getCostCode)
        .containsExactly("AUX_0201", "AUX_0202");
    assertThat(items.get(0).getBaseAmount()).isEqualByComparingTo("0.400000");
    assertThat(items.get(0).getRate()).isEqualByComparingTo("1.05");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("0.420000");
    assertThat(items.get(1).getBaseAmount()).isEqualByComparingTo("0.600000");
    assertThat(items.get(1).getRate()).isEqualByComparingTo("1.05");
    assertThat(items.get(1).getAmount()).isEqualByComparingTo("0.630000");
  }

  @Test
  @DisplayName("T9 包装辅料不进入成本计算辅料项")
  void packagingAuxSubjectExcludedFromCostItems() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto packagingCms = newAuxCost("0215", "包装辅料", "0.020000", "9.9900", "DIRECT");
    packagingCms.setMaterialCode("P-1");
    packagingCms.setSource("CMS_EFFECTIVE");
    AuxCostItemDto normalCms = newAuxCost("0201", "辅助焊料类", "0.400000", "9.9900", "DIRECT");
    normalCms.setMaterialCode("P-1");
    normalCms.setSource("CMS_EFFECTIVE");
    AuxCostItemDto packagingLegacy = newAuxCost("0215", "包装辅料", "10.000000", "0.1000", "RATE");
    packagingLegacy.setMaterialCode("P-1");
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-1"), "COMMERCIAL"))
        .thenReturn(List.of(packagingCms, normalCms));
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(packagingLegacy));

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-1"), 2026, "COMMERCIAL");

    assertThat(items).extracting(CostRunCostItemDto::getCostName)
        .containsExactly("辅助焊料类")
        .doesNotContain("包装辅料");
    assertThat(items.get(0).getCostCode()).isEqualTo("AUX_0201");
  }

  @Test
  @DisplayName("T15 核算工资优先读取 CMS 公共生效来源，不使用旧 CMS 派生工资")
  void calculationUsesCmsEffectiveSalarySources() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setApplyDate(LocalDate.of(2026, 5, 1));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("P-1");
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(formItemMapper.selectList(any())).thenReturn(List.of(item));

    SalaryCost oldCms = new SalaryCost();
    oldCms.setMaterialCode("P-1");
    oldCms.setSource("CMS");
    oldCms.setBusinessUnit("商用部品事业部");
    oldCms.setDirectLaborCost(new BigDecimal("99.000000"));
    oldCms.setIndirectLaborCost(new BigDecimal("88.000000"));
    when(salaryMapper.selectList(any())).thenReturn(List.of(oldCms));

    CmsCostSourceEffective direct = effective("SALARY_DIRECT", "P-1", "0301", "4.000000");
    CmsCostSourceEffective indirect = effective("SALARY_INDIRECT", "P-1", "0302", "0.220000");
    when(effectiveMapper.selectList(any())).thenReturn(List.of(direct, indirect));
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-1"), "COMMERCIAL")).thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any())).thenReturn(List.of());
    when(masterMapper.selectList(any())).thenReturn(List.of());
    when(rawMapper.selectList(any())).thenReturn(List.of());
    when(bomMapper.selectList(any())).thenReturn(List.of());
    CmsCostEffectiveSourceEnsureService ensureService = mock(CmsCostEffectiveSourceEnsureService.class);

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper,
            formItemMapper,
            salaryMapper,
            effectiveMapper,
            ensureService,
            auxMapper,
            partMapper,
            masterMapper,
            rawMapper,
            bomMapper);
    List<CostRunCostItemDto> items = svc.listByMaterialCodes("OA-1", "P-1", Set.of("P-1"), ignored -> {});

    CostRunCostItemDto directItem = findItem(items, "DIRECT_LABOR");
    CostRunCostItemDto indirectItem = findItem(items, "INDIRECT_LABOR");
    assertThat(directItem.getAmount()).isEqualByComparingTo("4.000000");
    assertThat(directItem.getRemark()).isNull();
    assertThat(indirectItem.getAmount()).isEqualByComparingTo("0.220000");
    assertThat(indirectItem.getRemark()).isNull();
    verify(ensureService).ensureDefaultSources(eq(2026), eq("SYSTEM_AUTO"), eq("COMMERCIAL"));
  }

  @Test
  @DisplayName("部门经费：CMS 生效工资金额可作为大修/工装/水电计算基数")
  void departmentFeesUseCmsEffectiveSalaryTotals() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    DepartmentFundRateMapper departmentMapper = mock(DepartmentFundRateMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-DEPT");
    form.setApplyDate(LocalDate.of(2026, 5, 1));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("1001900001090");
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(formItemMapper.selectList(any())).thenReturn(List.of(item));
    when(salaryMapper.selectList(any())).thenReturn(List.of());

    CmsCostSourceEffective direct =
        effective("SALARY_DIRECT", "1001900001090", "0301", "12.491320");
    CmsCostSourceEffective indirect =
        effective("SALARY_INDIRECT", "1001900001090", "0302", "0.322200");
    when(effectiveMapper.selectList(any())).thenReturn(List.of(direct, indirect));

    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode("1001900001090");
    raw.setProductionDivision("商用部品事业部");
    when(rawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of(raw));
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("1001900001090"), "COMMERCIAL"))
        .thenReturn(List.of());
    when(partMapper.selectList(any())).thenReturn(List.of());
    when(masterMapper.selectList(any())).thenReturn(List.of());
    when(bomMapper.selectList(any())).thenReturn(List.of());
    when(departmentMapper.selectOne(any()))
        .thenReturn(
            departmentRate("0.005100", "1.100000"),
            departmentRate("0.028300", "1.100000"),
            departmentRate("0.053000", "1.100000"),
            null);

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper,
            formItemMapper,
            salaryMapper,
            effectiveMapper,
            mock(CmsCostEffectiveSourceEnsureService.class),
            auxMapper,
            partMapper,
            masterMapper,
            rawMapper,
            bomMapper,
            mock(QualityLossRateMapper.class),
            mock(CostRunCacheLookupService.class),
            mock(ThreeExpenseRateMapper.class),
            departmentMapper);

    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("OA-DEPT", "1001900001090", Set.of("1001900001090"), ignored -> {});

    CostRunCostItemDto overhaul = findItem(items, "OVERHAUL");
    CostRunCostItemDto tooling = findItem(items, "TOOLING_REPAIR");
    CostRunCostItemDto water = findItem(items, "WATER_POWER");
    assertThat(overhaul.getBaseAmount()).isEqualByComparingTo("30.156554");
    assertThat(overhaul.getRate()).isEqualByComparingTo("0.005610");
    assertThat(overhaul.getAmount()).isEqualByComparingTo("0.169178");
    assertThat(tooling.getRate()).isEqualByComparingTo("0.031130");
    assertThat(tooling.getAmount()).isEqualByComparingTo("0.938774");
    assertThat(water.getRate()).isEqualByComparingTo("0.058300");
    assertThat(water.getAmount()).isEqualByComparingTo("1.758127");
    assertThat(water.getRemark()).isNull();
  }

  @Test
  @DisplayName("成本核算年度：按核算当天年份，不按 OA 申请日期年份")
  void calculationCostYearUsesCurrentYearInsteadOfApplyDateYear() {
    int currentYear = java.time.Year.now().getValue();
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    CmsCostEffectiveSourceEnsureService ensureService = mock(CmsCostEffectiveSourceEnsureService.class);

    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-OLD-APPLY");
    form.setApplyDate(LocalDate.of(currentYear - 1, 12, 31));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("P-CURRENT-YEAR");
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    when(salaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(effectiveMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(auxMapper.selectEffectiveAuxCostItems(currentYear, Set.of("P-CURRENT-YEAR"), "COMMERCIAL"))
        .thenReturn(List.of());
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of());
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper,
            formItemMapper,
            salaryMapper,
            effectiveMapper,
            ensureService,
            auxMapper,
            partMapper,
            masterMapper,
            rawMapper,
            bomMapper);

    svc.listByMaterialCodes(
        "OA-OLD-APPLY", "P-CURRENT-YEAR", Set.of("P-CURRENT-YEAR"), ignored -> {});

    verify(ensureService).ensureDefaultSources(eq(currentYear), eq("SYSTEM_AUTO"), eq("COMMERCIAL"));
    verify(auxMapper).selectEffectiveAuxCostItems(currentYear, Set.of("P-CURRENT-YEAR"), "COMMERCIAL");
  }

  @Test
  @DisplayName("T15 工资正式核算：只取当前料号公共生效来源，不用 lp_salary_cost 参考料号兜底")
  void calculationDoesNotFallbackToSalaryReferenceMaterial() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-REF");
    form.setApplyDate(LocalDate.of(2026, 5, 1));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("P-NEW");
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(formItemMapper.selectList(any())).thenReturn(List.of(item));

    SalaryCost referenceSelection = new SalaryCost();
    referenceSelection.setMaterialCode("P-NEW");
    referenceSelection.setRefMaterialCode("P-REF");
    referenceSelection.setBusinessUnit("商用部品事业部");
    referenceSelection.setDirectLaborCost(new BigDecimal("99.000000"));
    referenceSelection.setIndirectLaborCost(new BigDecimal("88.000000"));
    when(salaryMapper.selectList(any())).thenReturn(List.of(referenceSelection));

    CmsCostSourceEffective direct = effective("SALARY_DIRECT", "P-REF", "0301", "4.000000");
    CmsCostSourceEffective indirect = effective("SALARY_INDIRECT", "P-REF", "0302", "0.220000");
    when(effectiveMapper.selectList(any())).thenReturn(List.of(direct, indirect));
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-NEW"), "COMMERCIAL")).thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any())).thenReturn(List.of());
    when(masterMapper.selectList(any())).thenReturn(List.of());
    when(rawMapper.selectList(any())).thenReturn(List.of());
    when(bomMapper.selectList(any())).thenReturn(List.of());

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper,
            formItemMapper,
            salaryMapper,
            effectiveMapper,
            auxMapper,
            partMapper,
            masterMapper,
            rawMapper,
            bomMapper);
    List<CostRunCostItemDto> items = svc.listByMaterialCodes("OA-REF", "P-NEW", Set.of("P-NEW"), ignored -> {});

    CostRunCostItemDto directItem = findItem(items, "DIRECT_LABOR");
    CostRunCostItemDto indirectItem = findItem(items, "INDIRECT_LABOR");
    assertThat(directItem.getAmount()).isEqualByComparingTo("0.000000");
    assertThat(indirectItem.getAmount()).isEqualByComparingTo("0.000000");
    assertThat(directItem.getRemark()).contains("CMS 公共生效直接人工工资");
    assertThat(indirectItem.getRemark()).contains("CMS 公共生效辅助员工工资");
  }

  @Test
  @DisplayName("报价净损失率：成本核算优先按产品料号命中导入配置")
  void calculationLossRateUsesMaterialCodeMatch() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    QualityLossRateMapper qualityMapper = mock(QualityLossRateMapper.class);
    stubBasicCalculationInputs(
        formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper, masterMapper,
        rawMapper, bomMapper, "P-LOSS");
    QualityLossRate rate = new QualityLossRate();
    rate.setId(10L);
    rate.setLossRate(new BigDecimal("0.010000"));
    when(qualityMapper.selectOne(any(Wrapper.class))).thenReturn(rate);

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper,
            masterMapper, rawMapper, bomMapper, qualityMapper);
    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("OA-LOSS", "P-LOSS", Set.of("P-LOSS"), ignored -> {});

    CostRunCostItemDto lossItem = findItem(items, "LOSS");
    assertThat(lossItem.getRate()).isEqualByComparingTo("0.010000");
    assertThat(lossItem.getRemark()).isNull();
  }

  @Test
  @DisplayName("报价净损失率：料号未命中时用 raw 主档 material_model 按型号匹配")
  void calculationLossRateFallsBackToMaterialModel() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    QualityLossRateMapper qualityMapper = mock(QualityLossRateMapper.class);
    stubBasicCalculationInputs(
        formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper, masterMapper,
        rawMapper, bomMapper, "P-MODEL");
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode("P-MODEL");
    raw.setMaterialModel("MODEL-X");
    when(rawMapper.selectByLatestBatchAndCodes(any(), any())).thenReturn(List.of(raw));
    QualityLossRate modelRate = new QualityLossRate();
    modelRate.setId(11L);
    modelRate.setLossRate(new BigDecimal("0.020000"));
    when(qualityMapper.selectOne(any(Wrapper.class))).thenReturn(null, modelRate);

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper,
            masterMapper, rawMapper, bomMapper, qualityMapper);
    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("OA-LOSS", "P-MODEL", Set.of("P-MODEL"), ignored -> {});

    CostRunCostItemDto lossItem = findItem(items, "LOSS");
    assertThat(lossItem.getRate()).isEqualByComparingTo("0.020000");
    assertThat(lossItem.getRemark()).isNull();
  }

  @Test
  @DisplayName("Q10 三项费用：按报价单核算维度和维度映射命中费率")
  void threeExpenseRateUsesQuoteAccountingContext() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ThreeExpenseRateMapper threeExpenseRateMapper = mock(ThreeExpenseRateMapper.class);

    OaForm form = quoteFormWithAccountingContext();
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("P-Q10");
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    when(salaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(effectiveMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-Q10"), "COMMERCIAL"))
        .thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of());
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    form.setProcessCode("FI-SC-006");
    form.setOverseasSalesMode("否");
    ThreeExpenseRate rate = new ThreeExpenseRate();
    rate.setPeriodYear(2026);
    rate.setProductCategory("商用直销产品");
    rate.setProductLine("国内产线");
    rate.setApplicantDepartment("欧洲业务管理部-直销");
    rate.setApplicantOffice("");
    rate.setManagementExpenseRate(new BigDecimal("0.080000"));
    rate.setFinanceExpenseRate(new BigDecimal("0.020000"));
    rate.setSalesExpenseRate(new BigDecimal("0.200000"));
    when(threeExpenseRateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rate));

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper,
            masterMapper, rawMapper, bomMapper, mock(QualityLossRateMapper.class), lookup,
            threeExpenseRateMapper);
    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("OA-Q10", "P-Q10", Set.of("P-Q10"), ignored -> {});

    assertThat(findItem(items, "MGMT_EXP").getRate()).isEqualByComparingTo("0.080000");
    assertThat(findItem(items, "SALES_EXP").getRate()).isEqualByComparingTo("0.200000");
    assertThat(findItem(items, "FIN_EXP").getRate()).isEqualByComparingTo("0.020000");
    assertThat(findItem(items, "MGMT_EXP").getRemark()).isNull();
    verify(threeExpenseRateMapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("真实 FI-SC-006：海外仓终端客户命中商用国内产线海外部门费率")
  void realFiSc006OverseasWarehouseMatchesCommercialDomesticOverseasRate() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ThreeExpenseRateMapper threeExpenseRateMapper = mock(ThreeExpenseRateMapper.class);

    OaForm form = quoteFormWithAccountingContext();
    form.setOaNo("FI-SC-006-20260327-037");
    form.setProcessCode("FI-SC-006");
    form.setSourceBusinessDivision("商用压缩事业部");
    form.setOverseasSalesMode("是");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("1079900000536");
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    when(salaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(effectiveMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("1079900000536"), "COMMERCIAL"))
        .thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of());
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    ThreeExpenseRate rate = new ThreeExpenseRate();
    rate.setPeriodYear(2026);
    rate.setBusinessUnitType("COMMERCIAL");
    rate.setProductCategory("商用直销产品");
    rate.setProductLine("国内产线");
    rate.setApplicantDepartment("欧洲业务管理部-海外");
    rate.setApplicantOffice("/");
    rate.setManagementExpenseRate(new BigDecimal("0.100000"));
    rate.setFinanceExpenseRate(new BigDecimal("0.020000"));
    rate.setSalesExpenseRate(new BigDecimal("0.250000"));
    rate.setThreeExpenseTotalRate(new BigDecimal("0.370000"));
    when(threeExpenseRateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rate));

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper,
            masterMapper, rawMapper, bomMapper, mock(QualityLossRateMapper.class), lookup,
            threeExpenseRateMapper);
    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("FI-SC-006-20260327-037", "1079900000536", Set.of("1079900000536"), ignored -> {});

    assertThat(findItem(items, "MGMT_EXP").getRate()).isEqualByComparingTo("0.100000");
    assertThat(findItem(items, "SALES_EXP").getRate()).isEqualByComparingTo("0.250000");
    assertThat(findItem(items, "FIN_EXP").getRate()).isEqualByComparingTo("0.020000");
    assertThat(findItem(items, "MGMT_EXP").getRemark()).isNull();
    verify(threeExpenseRateMapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("Q10 三项费用：报价单核算维度缺失时返回明确 remark")
  void threeExpenseRateMissingDimensionReturnsRemark() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    SalaryCostMapper salaryMapper = mock(SalaryCostMapper.class);
    CmsCostSourceEffectiveMapper effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);

    OaForm form = quoteFormWithAccountingContext();
    form.setApplicantOffice(null);
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo("P-Q10");
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    when(salaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(effectiveMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-Q10"), "COMMERCIAL"))
        .thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of());
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunCostItemServiceImpl svc =
        buildForCalculation(
            formMapper, formItemMapper, salaryMapper, effectiveMapper, auxMapper, partMapper,
            masterMapper, rawMapper, bomMapper, mock(QualityLossRateMapper.class), lookup);
    List<CostRunCostItemDto> items =
        svc.listByMaterialCodes("OA-Q10", "P-Q10", Set.of("P-Q10"), ignored -> {});

    assertThat(findItem(items, "MGMT_EXP").getRate()).isNull();
    assertThat(findItem(items, "MGMT_EXP").getRemark())
        .contains("三项费用未命中")
        .contains("periodYear")
        .contains("sourceOffice");
  }

  @Test
  @DisplayName("TER-06 三项费用上下文：FI-SC-020/FI-SC-006 按事业部推导国内产线和商用后缀")
  void threeExpenseMatchContextCommercialDirectAndOverseas() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));

    CostRunCostItemServiceImpl.ThreeExpenseMatchContext sc020 =
        svc.buildThreeExpenseMatchContext(threeExpenseForm("FI-SC-020", "商用四通阀事业部", "是"));
    assertThat(sc020.periodYear).isEqualTo(Year.now().getValue());
    assertThat(sc020.productCategory).isEqualTo("商用直销产品");
    assertThat(sc020.productLine).isEqualTo("国内产线");
    assertThat(sc020.commercialOverseasFlag).isEqualTo("是");
    assertThat(sc020.matchedDepartment).isEqualTo("欧美业务管理部-海外");

    CostRunCostItemServiceImpl.ThreeExpenseMatchContext sc006 =
        svc.buildThreeExpenseMatchContext(threeExpenseForm("FI-SC-006", "商用四通阀事业部", "否"));
    assertThat(sc006.productCategory).isEqualTo("商用直销产品");
    assertThat(sc006.productLine).isEqualTo("国内产线");
    assertThat(sc006.commercialOverseasFlag).isEqualTo("否");
    assertThat(sc006.matchedDepartment).isEqualTo("欧美业务管理部-直销");
  }

  @Test
  @DisplayName("TER-06 三项费用上下文：FI-SC-005 产品事业部越南推导为越南事业部")
  void threeExpenseMatchContextCommercialVietnamProductDivision() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));

    CostRunCostItemServiceImpl.ThreeExpenseMatchContext context =
        svc.buildThreeExpenseMatchContext(threeExpenseForm("FI-SC-005", "越南产品事业部", "否"));

    assertThat(context.productCategory).isEqualTo("商用直销产品");
    assertThat(context.productLine).isEqualTo("越南事业部");
  }

  @Test
  @DisplayName("TER-06 三项费用上下文：家用单据所属事业部推导墨西哥产线，按代销/直销后缀")
  void threeExpenseMatchContextHouseholdMexicoSalesMode() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));

    CostRunCostItemServiceImpl.ThreeExpenseMatchContext context =
        svc.buildThreeExpenseMatchContext(threeExpenseForm("FI-SR-005", "墨西哥所属事业部", "是"));

    assertThat(context.productCategory).isEqualTo("家代商代销产品");
    assertThat(context.productLine).isEqualTo("墨西哥产线");
    assertThat(context.homeApplianceSalesModeFlag).isEqualTo("是");
    assertThat(context.matchedDepartment).isEqualTo("欧美业务管理部-代销");
    assertThat(context.commercialOverseasFlag).isNull();
  }

  @Test
  @DisplayName("TER-06 三项费用上下文：缺关键字段时返回明确原因")
  void threeExpenseMatchContextMissingDivisionHasReason() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));
    OaForm form = threeExpenseForm("FI-SC-006", null, "否");

    CostRunCostItemServiceImpl.ThreeExpenseMatchContext context =
        svc.buildThreeExpenseMatchContext(form);

    assertThat(context.missingReason).contains("缺少事业部字段");
  }

  @Test
  @DisplayName("TER-07 三项费用匹配：配置处室有具体值时优先按 OA 申请处室命中")
  void threeExpenseCandidatePrefersOfficeMatch() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));
    OaForm form = threeExpenseForm("FI-SC-006", "商用四通阀事业部", "否");
    form.setApplicantOffice("欧洲业务管理部");
    CostRunCostItemServiceImpl.ThreeExpenseMatchContext context =
        svc.buildThreeExpenseMatchContext(form);

    ThreeExpenseRate departmentRate = threeExpenseCandidate("", "欧美业务管理部-直销", "0.080000");
    ThreeExpenseRate officeRate = threeExpenseCandidate("欧洲业务管理部", "其他部门-直销", "0.090000");

    ThreeExpenseRate matched = svc.matchThreeExpenseRateCandidate(context, List.of(departmentRate, officeRate));

    assertThat(matched).isSameAs(officeRate);
  }

  @Test
  @DisplayName("TER-07 三项费用匹配：配置处室为空或 / 时按申请部门后缀命中")
  void threeExpenseCandidateFallsBackToMatchedDepartment() {
    CostRunCostItemServiceImpl svc = buildForCalculation(
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(MaterialMasterMapper.class),
        mock(MaterialMasterRawMapper.class),
        mock(BomRawHierarchyMapper.class));
    CostRunCostItemServiceImpl.ThreeExpenseMatchContext context =
        svc.buildThreeExpenseMatchContext(threeExpenseForm("FI-SC-006", "商用四通阀事业部", "否"));

    ThreeExpenseRate slashOfficeRate = threeExpenseCandidate("/", "欧美业务管理部-直销", "0.080000");

    ThreeExpenseRate matched = svc.matchThreeExpenseRateCandidate(context, List.of(slashOfficeRate));

    assertThat(matched).isSameAs(slashOfficeRate);
  }

  @Test
  @DisplayName("T15 辅料正式核算：只取当前料号公共生效来源，不用 lp_aux_subject 参考料号兜底")
  void auxCmsEffectiveDoesNotFallbackToReferenceMaterial() {
    AuxCostItemMapper auxMapper = mock(AuxCostItemMapper.class);
    AuxCostItemDto referenceSelection = newAuxCost("0201", "旧复制辅料", "99.000000", "0.1000", "RATE");
    referenceSelection.setMaterialCode("P-NEW");
    referenceSelection.setRefMaterialCode("P-REF");
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of(referenceSelection));
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of("P-NEW"), "COMMERCIAL"))
        .thenReturn(List.of());

    CostRunCostItemServiceImpl svc = buildWithAuxMapper(auxMapper);
    List<CostRunCostItemDto> items = svc.buildAuxItems(Set.of("P-NEW"), 2026, "COMMERCIAL");

    assertThat(items).isEmpty();
    verify(auxMapper).selectEffectiveAuxCostItems(2026, Set.of("P-NEW"), "COMMERCIAL");
  }

  @Test
  @DisplayName("产品属性系数：命中 lp_product_property → 返回 coefficient")
  void coefficientHit() {
    // T19：lookup 走 cacheLookup（@Cacheable），不再直接 mapper
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NON-STD");
    property.setCoefficient(new BigDecimal("1.2500"));
    when(lookup.findProductProperty("P-NON-STD")).thenReturn(property);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-NON-STD"))
        .isEqualByComparingTo(new BigDecimal("1.2500"));
  }

  @Test
  @DisplayName("PPA-06 产品属性系数：按年度和业务单元查询，避免跨年复用")
  void coefficientHitByYearAndBusinessUnit() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ProductProperty property = new ProductProperty();
    property.setProductCode("P-ANNUAL");
    property.setPropertyYear(2026);
    property.setBusinessUnitType("COMMERCIAL");
    property.setCoefficient(new BigDecimal("1.3500"));
    when(lookup.findProductProperty("P-ANNUAL", 2026, "COMMERCIAL")).thenReturn(property);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    CostRunCostItemServiceImpl.ProductCoefficientLookup result =
        svc.lookupProductCoefficient("P-ANNUAL", 2026, "COMMERCIAL");

    assertThat(result.coefficient()).isEqualByComparingTo("1.3500");
    assertThat(result.remark()).isNull();
    verify(lookup).findProductProperty("P-ANNUAL", 2026, "COMMERCIAL");
  }

  @Test
  @DisplayName("产品属性系数：查无记录 → 回落 1（标准品兜底）")
  void coefficientMissDefault() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    when(lookup.findProductProperty("P-UNKNOWN")).thenReturn(null);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-UNKNOWN"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：coefficient 字段为 null → 也回落 1")
  void coefficientNullDefault() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NULL-COEF");
    property.setCoefficient(null);
    when(lookup.findProductProperty("P-NULL-COEF")).thenReturn(property);

    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);
    assertThat(svc.lookupProductCoefficient("P-NULL-COEF"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：productCode 为空 → 直接返回 1，不查库")
  void coefficientBlankCode() {
    CostRunCacheLookupService lookup = mock(CostRunCacheLookupService.class);
    CostRunCostItemServiceImpl svc = buildWithLookup(lookup);

    assertThat(svc.lookupProductCoefficient(null)).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(svc.lookupProductCoefficient("  ")).isEqualByComparingTo(BigDecimal.ONE);
    // 没有任何查库行为
    org.mockito.Mockito.verifyNoInteractions(lookup);
  }

  // -------- T11 splitPartAmount --------

  @Test
  @DisplayName("T11 splitPartAmount: 多部品按 cost_element 拆分包装/非包装")
  void splitPartAmount_buckets() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    // 4 个部品：2 包装 + 2 非包装；amount 含 1 个 NULL（验证 NULL 跳过）
    CostRunPartItem p1 = newPart("PKG-001", new BigDecimal("10.000000"));
    CostRunPartItem p2 = newPart("PKG-002", new BigDecimal("5.500000"));
    CostRunPartItem p3 = newPart("RAW-001", new BigDecimal("20.000000"));
    CostRunPartItem p4 = newPart("RAW-002", null); // NULL 应被跳过
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p1, p2, p3, p4));

    // 主档：PKG-001 / PKG-002 是包装材料，RAW-* 不返回（被 .eq(cost_element=包装) 过滤掉）
    when(masterMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newMaster("PKG-001"), newMaster("PKG-002")));

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    var split = svc.splitPartAmount("OA-1", "P-1");

    // 包装总额 = 10 + 5.5 = 15.5；非包装 = 20.0（NULL 跳）
    assertThat(split.packageTotal()).isEqualByComparingTo(new BigDecimal("15.500000"));
    assertThat(split.nonPackageTotal()).isEqualByComparingTo(new BigDecimal("20.000000"));
  }

  @Test
  @DisplayName("T11 splitPartAmount: 主档查无包装件 → 全部归非包装")
  void splitPartAmount_noPackageRow() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newPart("RAW-A", new BigDecimal("3.000000"))));
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of()); // 无包装

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    var split = svc.splitPartAmount("OA-1", "P-1");

    assertThat(split.packageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(split.nonPackageTotal()).isEqualByComparingTo(new BigDecimal("3.000000"));
  }

  // -------- T11 lookupFreight --------

  @Test
  @DisplayName("T11 lookupFreight: 命中 OaFormItem.shipping_fee → 求和")
  void lookupFreight_hit() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);

    OaForm form = new OaForm();
    form.setId(99L);
    form.setOaNo("OA-1");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);

    OaFormItem r1 = new OaFormItem();
    r1.setShippingFee(new BigDecimal("1.250000"));
    OaFormItem r2 = new OaFormItem();
    r2.setShippingFee(new BigDecimal("0.500000"));
    OaFormItem r3 = new OaFormItem();
    r3.setShippingFee(null); // NULL 跳过
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(r1, r2, r3));

    CostRunCostItemServiceImpl svc =
        buildWith(mock(CostRunPartItemMapper.class), mock(MaterialMasterMapper.class), formMapper, formItemMapper);
    BigDecimal freight = svc.lookupFreight("OA-1", "P-1");

    assertThat(freight).isEqualByComparingTo(new BigDecimal("1.750000"));
  }

  @Test
  @DisplayName("T11 lookupFreight: OA 不存在 → 0（不抛错）")
  void lookupFreight_noForm() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    CostRunCostItemServiceImpl svc =
        buildWith(mock(CostRunPartItemMapper.class), mock(MaterialMasterMapper.class), formMapper, mock(OaFormItemMapper.class));
    assertThat(svc.lookupFreight("OA-MISSING", "P-1")).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // -------- T24 BOM_BUCKET 焊料 / 包装聚合 --------

  @Test
  @DisplayName("T24 焊料聚合：part 7 行命中焊料子件 → SUM amount")
  void bucketWeld_happyPath() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    // mock part_item：3 行焊料 + 1 行非焊料
    CostRunPartItem p1 = newPart("301010012", new BigDecimal("1.625677"));
    CostRunPartItem p2 = newPart("301020895", new BigDecimal("1.101030"));
    CostRunPartItem p3 = newPart("301030001", new BigDecimal("0.200252"));
    CostRunPartItem pOther = newPart("203250606", new BigDecimal("0.788363"));
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(p1, p2, p3, pOther));

    // mock material_master：只返焊料 3 行（master 已经被 cost_element=焊料 过滤）
    when(masterMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newWeldMaster("301010012"), newWeldMaster("301020895"), newWeldMaster("301030001")));

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    BigDecimal sum = svc.sumPartByCostElement("OA-1", "P-1", "主要材料-焊料");

    // 1.625677 + 1.101030 + 0.200252 = 2.926959；非焊料 part 不计入
    assertThat(sum).isEqualByComparingTo(new BigDecimal("2.926959"));
  }

  @Test
  @DisplayName("T24 焊料聚合：part 没有焊料子件 → 返 0（buildBucketItems 跳过此行）")
  void bucketWeld_emptyMaster() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = mock(MaterialMasterMapper.class);

    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(newPart("RAW-001", new BigDecimal("5.000000"))));
    // 主档查无焊料命中
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunCostItemServiceImpl svc = buildWith(partMapper, masterMapper, mock(OaFormMapper.class), mock(OaFormItemMapper.class));
    assertThat(svc.sumPartByCostElement("OA-1", "P-1", "主要材料-焊料"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("T24 包装聚合：包装组件父件金额 × 1.05")
  void bucketPackage_happyPath() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of(newRawMaster("9830000026238", "包装组件")));
    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            newPart("9830000026238", new BigDecimal("1.113105")),
            newPart("NORMAL", new BigDecimal("5.000000"))));

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    assertThat(svc.sumPackageComponentParentAmount("OA-1", "1079900000536"))
        .isEqualByComparingTo(new BigDecimal("1.113105"));
    assertThat(svc.calculatePackageBucketAmount("OA-1", "1079900000536"))
        .isEqualByComparingTo(new BigDecimal("1.168760"));
  }

  @Test
  @DisplayName("T24 包装聚合：BOM 找不到包装组件父件 → 返 0（buildBucketItems 跳过此行）")
  void bucketPackage_noParent() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    // raw 主档无包装组件
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any())).thenReturn(List.of());

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    assertThat(svc.sumPackageComponentParentAmount("OA-1", "P-1"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("T24 包装聚合：raw 有包装父件但 part_item 没父件金额 → 返 0")
  void bucketPackage_noBomChildren() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);

    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of(newRawMaster("9830000026238", "包装组件")));
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of(newPart("NORMAL", BigDecimal.ONE)));

    CostRunCostItemServiceImpl svc = buildBucketSvc(partMapper, rawMapper, bomMapper);
    assertThat(svc.sumPackageComponentParentAmount("OA-1", "P-1"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ---------- 辅助 ----------

  private static CostRunPartItem newPart(String code, BigDecimal amount) {
    CostRunPartItem p = new CostRunPartItem();
    p.setPartCode(code);
    p.setAmount(amount);
    return p;
  }

  private static MaterialMaster newMaster(String code) {
    MaterialMaster m = new MaterialMaster();
    m.setMaterialCode(code);
    m.setCostElement("主要材料-包装材料");
    return m;
  }

  /** T24：焊料 master mock — cost_element=主要材料-焊料 */
  private static MaterialMaster newWeldMaster(String code) {
    MaterialMaster m = new MaterialMaster();
    m.setMaterialCode(code);
    m.setCostElement("主要材料-焊料");
    return m;
  }

  /** T24：raw 主档 mock — main_category_name=指定值（用于包装组件父件） */
  private static MaterialMasterRaw newRawMaster(String code, String mainCategoryName) {
    MaterialMasterRaw m = new MaterialMasterRaw();
    m.setMaterialCode(code);
    m.setMainCategoryName(mainCategoryName);
    return m;
  }

  /** T24：BOM hierarchy mock — material_code=指定值（视为父件下挂的子件） */
  private static BomRawHierarchy newBomChild(String materialCode) {
    BomRawHierarchy h = new BomRawHierarchy();
    h.setMaterialCode(materialCode);
    return h;
  }

  private void stubBasicCalculationInputs(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      String productCode) {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-LOSS");
    form.setApplyDate(LocalDate.of(2026, 5, 1));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo(productCode);
    item.setValidDate(LocalDate.of(2026, 6, 1));
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(formItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    when(salaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(effectiveMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(auxMapper.selectEffectiveAuxCostItems(2026, Set.of(productCode), "COMMERCIAL"))
        .thenReturn(List.of());
    when(auxMapper.selectByMaterialCodes(any())).thenReturn(List.of());
    when(partMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of());
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
  }

  /** T24 单测专用：注入 part / raw / bom 3 个 mapper（包装聚合用），其余 mock 兜底 */
  private CostRunCostItemServiceImpl buildBucketSvc(
      CostRunPartItemMapper partMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(CmsCostEffectiveSourceEnsureService.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        partMapper,
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        mock(MaterialMasterMapper.class),
        rawMapper,
        bomMapper,
        mock(CostRunCacheLookupService.class),
        false);
  }

  /** T19：T19 之前的 4 个 coefficient test 走这个 helper（直接 mock cacheLookup） */
  private CostRunCostItemServiceImpl buildWithLookup(CostRunCacheLookupService lookup) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(CmsCostEffectiveSourceEnsureService.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        mock(MaterialMasterMapper.class),
        mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        lookup,
        false);
  }

  private CostRunCostItemServiceImpl buildWithAuxMapper(AuxCostItemMapper auxMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(CmsCostEffectiveSourceEnsureService.class),
        mock(DepartmentFundRateMapper.class),
        auxMapper,
        mock(CostRunPartItemMapper.class),
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        mock(MaterialMasterMapper.class),
        mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        mock(CostRunCacheLookupService.class),
        false);
  }

  private static AuxCostItemDto newAuxCost(
      String code, String name, String unitPrice, String floatRate, String amountCalcMode) {
    AuxCostItemDto dto = new AuxCostItemDto();
    dto.setAuxSubjectCode(code);
    dto.setAuxSubjectName(name);
    dto.setUnitPrice(new BigDecimal(unitPrice));
    dto.setFloatRate(new BigDecimal(floatRate));
    dto.setAmountCalcMode(amountCalcMode);
    return dto;
  }

  private static CmsCostSourceEffective effective(
      String sourceType, String parentCode, String subjectCode, String amount) {
    CmsCostSourceEffective effective = new CmsCostSourceEffective();
    effective.setCostYear(2026);
    effective.setSourceType(sourceType);
    effective.setParentCode(parentCode);
    effective.setSubjectCode(subjectCode);
    effective.setAmountYuan(new BigDecimal(amount));
    effective.setBusinessUnitType("COMMERCIAL");
    return effective;
  }

  private static DepartmentFundRate departmentRate(String quoteRatio, String upliftRatio) {
    DepartmentFundRate rate = new DepartmentFundRate();
    rate.setManhourRate(new BigDecimal("0.424900"));
    rate.setQuoteRatio(new BigDecimal(quoteRatio));
    rate.setUpliftRatio(new BigDecimal(upliftRatio));
    return rate;
  }

  private static CostRunCostItemDto findItem(List<CostRunCostItemDto> items, String costCode) {
    return items.stream()
        .filter(item -> costCode.equals(item.getCostCode()))
        .findFirst()
        .orElseThrow();
  }

  private static OaForm quoteFormWithAccountingContext() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-Q10");
    form.setApplyDate(LocalDate.of(2026, 3, 27));
    form.setBusinessUnitType("COMMERCIAL");
    form.setAccountingPeriodMonth("2026-03");
    form.setSourceCompany("浙江三花商用制冷有限公司");
    form.setSourceBusinessDivision("商用四通阀事业部");
    form.setApplicantDept("欧美业务管理部");
    form.setApplicantOffice("欧洲业务管理部");
    form.setExpenseProductCategory("商用直销产品");
    return form;
  }

  private static ThreeExpenseDimensionMapping mapping(String standardValue) {
    ThreeExpenseDimensionMapping mapping = new ThreeExpenseDimensionMapping();
    mapping.setStandardValue(standardValue);
    return mapping;
  }

  private static OaForm threeExpenseForm(String processCode, String division, String overseasSalesMode) {
    OaForm form = new OaForm();
    form.setOaNo(processCode + "-20260525-001");
    form.setProcessCode(processCode);
    form.setBusinessUnitType(processCode.startsWith("FI-SC") ? "COMMERCIAL" : "HOUSEHOLD");
    form.setSourceBusinessDivision(division);
    form.setApplicantDept("欧美业务管理部");
    form.setApplicantOffice("/");
    form.setOverseasSalesMode(overseasSalesMode);
    return form;
  }

  private static ThreeExpenseRate threeExpenseCandidate(
      String applicantOffice, String applicantDepartment, String managementRate) {
    ThreeExpenseRate rate = new ThreeExpenseRate();
    rate.setApplicantOffice(applicantOffice);
    rate.setApplicantDepartment(applicantDepartment);
    rate.setManagementExpenseRate(new BigDecimal(managementRate));
    rate.setFinanceExpenseRate(new BigDecimal("0.020000"));
    rate.setSalesExpenseRate(new BigDecimal("0.030000"));
    rate.setOemExpenseRate(new BigDecimal("0.990000"));
    return rate;
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        mock(QualityLossRateMapper.class));
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        mock(CmsCostEffectiveSourceEnsureService.class),
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper);
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostEffectiveSourceEnsureService ensureService,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        ensureService,
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        mock(QualityLossRateMapper.class));
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostEffectiveSourceEnsureService ensureService,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        ensureService,
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper,
        mock(CostRunCacheLookupService.class));
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper,
      CostRunCacheLookupService lookup) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        mock(CmsCostEffectiveSourceEnsureService.class),
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper,
        lookup);
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostEffectiveSourceEnsureService ensureService,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper,
      CostRunCacheLookupService lookup) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        ensureService,
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper,
        lookup,
        mock(ThreeExpenseRateMapper.class));
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper,
      CostRunCacheLookupService lookup,
      ThreeExpenseRateMapper threeExpenseRateMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        mock(CmsCostEffectiveSourceEnsureService.class),
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper,
        lookup,
        threeExpenseRateMapper);
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostEffectiveSourceEnsureService ensureService,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper,
      CostRunCacheLookupService lookup,
      ThreeExpenseRateMapper threeExpenseRateMapper) {
    return buildForCalculation(
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        ensureService,
        auxMapper,
        partMapper,
        masterMapper,
        rawMapper,
        bomMapper,
        qualityMapper,
        lookup,
        threeExpenseRateMapper,
        mock(DepartmentFundRateMapper.class));
  }

  private CostRunCostItemServiceImpl buildForCalculation(
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper,
      SalaryCostMapper salaryMapper,
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostEffectiveSourceEnsureService ensureService,
      AuxCostItemMapper auxMapper,
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      MaterialMasterRawMapper rawMapper,
      BomRawHierarchyMapper bomMapper,
      QualityLossRateMapper qualityMapper,
      CostRunCacheLookupService lookup,
      ThreeExpenseRateMapper threeExpenseRateMapper,
      DepartmentFundRateMapper departmentFundRateMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        formMapper,
        formItemMapper,
        salaryMapper,
        effectiveMapper,
        ensureService,
        departmentFundRateMapper,
        auxMapper,
        partMapper,
        qualityMapper,
        mock(ManufactureRateMapper.class),
        threeExpenseRateMapper,
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        masterMapper,
        rawMapper,
        bomMapper,
        lookup,
        false);
  }

  /** T11 单测专用：只关心 part / master / oaForm* 4 个 mapper，其余 mock 兜底 */
  private CostRunCostItemServiceImpl buildWith(
      CostRunPartItemMapper partMapper,
      MaterialMasterMapper masterMapper,
      OaFormMapper formMapper,
      OaFormItemMapper formItemMapper) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        formMapper,
        formItemMapper,
        mock(SalaryCostMapper.class),
        mock(CmsCostSourceEffectiveMapper.class),
        mock(CmsCostEffectiveSourceEnsureService.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        partMapper,
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        mock(ProductPropertyMapper.class),
        masterMapper,
        mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        mock(CostRunCacheLookupService.class),
        false);
  }
}
