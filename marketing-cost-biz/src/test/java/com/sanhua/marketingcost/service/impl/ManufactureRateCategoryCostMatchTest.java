package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManufactureRateCategoryCostMatchTest {

  static {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ManufactureRate.class);
  }

  @Test
  @DisplayName("制造费用率核算：板换J生产分类命中J系列12%配置")
  void matchesFinishedProductCategoryPrefix() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    ManufactureRate categoryRate = manufactureRate("0.120000");
    when(rateMapper.selectOne(any())).thenReturn(null, null, null, categoryRate);

    List<CostRunCostItemDto> items =
        calculate(rateMapper, raw("J11AH-18L-02", "J11"));

    CostRunCostItemDto manufacture = findManufacture(items);
    assertThat(manufacture.getRate()).isEqualByComparingTo("0.120000");
    assertThat(manufacture.getRemark()).isNull();
  }

  @Test
  @DisplayName("制造费用率核算：S6C具体型号15%优先于S系列16%")
  void exactModelWinsBeforeCategoryPrefix() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    ManufactureRate modelRate = manufactureRate("0.150000");
    when(rateMapper.selectOne(any()))
        .thenAnswer(
            invocation -> {
              AbstractWrapper<ManufactureRate, ?, ?> query = invocation.getArgument(0);
              query.getSqlSegment();
              return query.getParamNameValuePairs().containsValue("板换事业部::S6CH-34H-16")
                  ? modelRate
                  : null;
            });

    List<CostRunCostItemDto> items =
        calculate(rateMapper, raw("S6CH-34H-16", "S6"));

    CostRunCostItemDto manufacture = findManufacture(items);
    assertThat(manufacture.getRate()).isEqualByComparingTo("0.150000");
  }

  @Test
  @DisplayName("制造费用率核算：相同型号不能跨事业部命中")
  void sameModelDoesNotCrossDivision() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    ManufactureRate electronicRate = manufactureRate("0.120000");
    when(rateMapper.selectOne(any()))
        .thenAnswer(
            invocation -> {
              AbstractWrapper<ManufactureRate, ?, ?> query = invocation.getArgument(0);
              query.getSqlSegment();
              return query.getParamNameValuePairs()
                      .containsValue("电子产品事业部::FQ-A20110-000001")
                  ? electronicRate
                  : null;
            });

    CostRunCostItemDto electronic =
        findManufacture(
            calculate(
                rateMapper,
                raw("FQ-A20110-000001", "FQ", "电子产品事业部")));
    CostRunCostItemDto coil =
        findManufacture(
            calculate(rateMapper, raw("FQ-A20110-000001", "FQ", "线圈事业部")));

    assertThat(electronic.getRate()).isEqualByComparingTo("0.120000");
    assertThat(coil.getRate()).isNull();
    assertThat(coil.getRemark()).contains("线圈事业部/FQ-A20110-000001 无型号级配置");
  }

  private List<CostRunCostItemDto> calculate(
      ManufactureRateMapper rateMapper, MaterialMasterRaw raw) {
    return calculate(rateMapper, raw, null);
  }

  @Test
  @DisplayName("制造费用率核算：成品料品档案不存在时按OA事业部匹配年度和业务单元费率")
  void missingMaterialMasterUsesOaDivision() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    stubDivisionRate(rateMapper, "电子产品事业部", "0.150000");

    CostRunCostItemDto manufacture =
        findManufacture(calculate(rateMapper, null, " 电子产品事业部 "));

    assertThat(manufacture.getRate()).isEqualByComparingTo("0.150000");
    assertThat(manufacture.getRemark()).contains("OA事业部", "电子产品事业部");
  }

  @Test
  @DisplayName("制造费用率核算：有料号级配置时仍优先于OA事业部")
  void materialCodeRateWinsBeforeOaDivision() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    when(rateMapper.selectOne(any())).thenAnswer(invocation -> {
      AbstractWrapper<ManufactureRate, ?, ?> query = invocation.getArgument(0);
      query.getSqlSegment();
      assertThat(query.getParamNameValuePairs().values()).doesNotContain("电子产品事业部");
      return manufactureRate("0.200000");
    });

    assertThat(findManufacture(calculate(rateMapper, null, "电子产品事业部")).getRate())
        .isEqualByComparingTo("0.200000");
  }

  @Test
  @DisplayName("制造费用率核算：已有料品档案时保留主档事业部优先级")
  void existingMaterialMasterKeepsItsDivision() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    stubDivisionRate(rateMapper, "板换事业部", "0.180000");

    CostRunCostItemDto manufacture =
        findManufacture(calculate(rateMapper, raw("J11AH", "J11"), "电子产品事业部"));

    assertThat(manufacture.getRate()).isEqualByComparingTo("0.180000");
    assertThat(manufacture.getRemark()).isNull();
  }

  @Test
  @DisplayName("制造费用率核算：主档存在但费率未配置或事业部为空时不能改用OA掩盖缺失")
  void existingMaterialMasterDoesNotFallBackOnRateMiss() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    stubDivisionRate(rateMapper, "电子产品事业部", "0.150000");

    for (String masterDivision : new String[] {"板换事业部", null}) {
      CostRunCostItemDto manufacture = findManufacture(calculate(
          rateMapper, raw("J11AH", "J11", masterDivision), "电子产品事业部"));
      assertThat(manufacture.getRate()).isNull();
      assertThat(manufacture.getRemark()).contains("制造费用率未命中");
    }
  }

  @Test
  @DisplayName("制造费用率核算：无主档且OA事业部为空时给出明确原因")
  void missingMaterialAndOaDivisionRemainMissing() {
    CostRunCostItemDto manufacture = findManufacture(
        calculate(mock(ManufactureRateMapper.class), null, "  "));

    assertThat(manufacture.getRate()).isNull();
    assertThat(manufacture.getRemark()).contains("OA事业部为空");
  }

  @Test
  @DisplayName("制造费用率核算：OA事业部未配置费率时保留缺失并说明匹配维度")
  void unconfiguredOaDivisionRemainsMissing() {
    CostRunCostItemDto manufacture = findManufacture(
        calculate(mock(ManufactureRateMapper.class), null, "电子产品事业部"));

    assertThat(manufacture.getRate()).isNull();
    assertThat(manufacture.getRemark())
        .contains("OA事业部 电子产品事业部 无事业部级配置", "businessUnitType=COMMERCIAL");
  }

  @Test
  @DisplayName("制造费用率核算：OA事业部有效零费率保留为零")
  void zeroOaDivisionRateIsValid() {
    ManufactureRateMapper rateMapper = mock(ManufactureRateMapper.class);
    stubDivisionRate(rateMapper, "电子产品事业部", "0.000000");

    assertThat(findManufacture(calculate(rateMapper, null, "电子产品事业部")).getRate())
        .isEqualByComparingTo("0.000000");
  }

  private static void stubDivisionRate(
      ManufactureRateMapper rateMapper, String division, String value) {
    when(rateMapper.selectOne(any())).thenAnswer(invocation -> {
      AbstractWrapper<ManufactureRate, ?, ?> query = invocation.getArgument(0);
      query.getSqlSegment();
      var parameters = query.getParamNameValuePairs().values();
      assertThat(parameters).contains(Year.now().getValue(), "COMMERCIAL");
      return parameters.contains("DIVISION") && parameters.contains(division)
          ? manufactureRate(value) : null;
    });
  }

  private List<CostRunCostItemDto> calculate(
      ManufactureRateMapper rateMapper, MaterialMasterRaw raw, String oaDivision) {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-MFR-CATEGORY");
    form.setBusinessUnitType("COMMERCIAL");
    form.setSourceBusinessDivision(oaDivision);
    String materialCode = raw == null ? "FINISHED-PLATE" : raw.getMaterialCode();
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo(materialCode);
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(formItemMapper.selectList(any())).thenReturn(List.of(item));
    when(rawMapper.selectByLatestBatchAndCodes(any(), any(), eq("COMMERCIAL")))
        .thenReturn(raw == null ? List.of() : List.of(raw));

    CostRunCostItemServiceImpl service =
        new CostRunCostItemServiceImpl(
            mock(CostRunCostItemMapper.class),
            formMapper,
            formItemMapper,
            mock(CmsCostSourceEffectiveMapper.class),
            mock(DepartmentFundRateMapper.class),
            mock(AuxCostItemMapper.class),
            mock(CostRunPartItemMapper.class),
            mock(QualityLossRateMapper.class),
            rateMapper,
            mock(ThreeExpenseRateMapper.class),
            mock(OtherExpenseRateMapper.class),
            mock(MaterialMasterMapper.class),
            rawMapper,
            mock(BomRawHierarchyMapper.class),
            mock(CostRunCacheLookupService.class));
    CostRunContext context =
        CostRunContext.quote(
            form.getOaNo(), 1L, materialCode, null, null, "COMMERCIAL", "2026-08", "TEST");
    context.setPriceOrgCode("210");
    context.setMaterialOrganizationCode("COMMERCIAL");
    return service.listByMaterialCodes(
        form.getOaNo(),
        materialCode,
        Set.of(materialCode),
        context,
        List.of(),
        false,
        ignored -> {});
  }

  private static MaterialMasterRaw raw(String model, String productionCategory) {
    return raw(model, productionCategory, "板换事业部");
  }

  private static MaterialMasterRaw raw(
      String model, String productionCategory, String productionDivision) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode("FINISHED-PLATE");
    raw.setMaterialModel(model);
    raw.setMaterialName("钎焊板式换热器");
    raw.setProductionDivision(productionDivision);
    raw.setProductionCategory(productionCategory);
    return raw;
  }

  private static ManufactureRate manufactureRate(String value) {
    ManufactureRate rate = new ManufactureRate();
    rate.setId(1L);
    rate.setFeeRate(new BigDecimal(value));
    return rate;
  }

  private static CostRunCostItemDto findManufacture(List<CostRunCostItemDto> items) {
    return items.stream()
        .filter(item -> "MANUFACTURE".equals(item.getCostCode()))
        .findFirst()
        .orElseThrow();
  }
}
