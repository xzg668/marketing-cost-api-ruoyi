package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManufactureRateCategoryCostMatchTest {

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
    when(rateMapper.selectOne(any())).thenReturn(null, modelRate);

    List<CostRunCostItemDto> items =
        calculate(rateMapper, raw("S6CH-34H-16", "S6"));

    CostRunCostItemDto manufacture = findManufacture(items);
    assertThat(manufacture.getRate()).isEqualByComparingTo("0.150000");
  }

  private List<CostRunCostItemDto> calculate(
      ManufactureRateMapper rateMapper, MaterialMasterRaw raw) {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper formItemMapper = mock(OaFormItemMapper.class);
    MaterialMasterRawMapper rawMapper = mock(MaterialMasterRawMapper.class);
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-MFR-CATEGORY");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setOaFormId(1L);
    item.setMaterialNo(raw.getMaterialCode());
    item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(formItemMapper.selectList(any())).thenReturn(List.of(item));
    when(rawMapper.selectByLatestBatchAndCodes(any(), any(), eq("COMMERCIAL")))
        .thenReturn(List.of(raw));

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
            mock(ProductPropertyMapper.class),
            mock(MaterialMasterMapper.class),
            rawMapper,
            mock(BomRawHierarchyMapper.class),
            mock(CostRunCacheLookupService.class));
    CostRunContext context =
        CostRunContext.quote(
            form.getOaNo(), 1L, raw.getMaterialCode(), null, null, "COMMERCIAL", "2026-08", "TEST");
    context.setPriceOrgCode("210");
    context.setMaterialOrganizationCode("COMMERCIAL");
    return service.listByMaterialCodes(
        form.getOaNo(),
        raw.getMaterialCode(),
        Set.of(raw.getMaterialCode()),
        context,
        List.of(),
        false,
        ignored -> {});
  }

  private static MaterialMasterRaw raw(String model, String productionCategory) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode("FINISHED-PLATE");
    raw.setMaterialModel(model);
    raw.setMaterialName("钎焊板式换热器");
    raw.setProductionDivision("板换事业部");
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
