package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import java.math.BigDecimal;
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
  @DisplayName("产品属性系数：命中 lp_product_property → 返回 coefficient")
  void coefficientHit() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NON-STD");
    property.setCoefficient(new BigDecimal("1.2500"));
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(property);

    CostRunCostItemServiceImpl svc = build(mapper, false);
    assertThat(svc.lookupProductCoefficient("P-NON-STD"))
        .isEqualByComparingTo(new BigDecimal("1.2500"));
  }

  @Test
  @DisplayName("产品属性系数：查无记录 → 回落 1（标准品兜底）")
  void coefficientMissDefault() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    CostRunCostItemServiceImpl svc = build(mapper, false);
    assertThat(svc.lookupProductCoefficient("P-UNKNOWN"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：coefficient 字段为 null → 也回落 1")
  void coefficientNullDefault() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    ProductProperty property = new ProductProperty();
    property.setParentCode("P-NULL-COEF");
    property.setCoefficient(null);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(property);

    CostRunCostItemServiceImpl svc = build(mapper, false);
    assertThat(svc.lookupProductCoefficient("P-NULL-COEF"))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  @DisplayName("产品属性系数：productCode 为空 → 直接返回 1，不查库")
  void coefficientBlankCode() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    CostRunCostItemServiceImpl svc = build(mapper, false);

    assertThat(svc.lookupProductCoefficient(null)).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(svc.lookupProductCoefficient("  ")).isEqualByComparingTo(BigDecimal.ONE);
    // 没有任何查库行为
    org.mockito.Mockito.verifyNoInteractions(mapper);
  }

  // ---------- 辅助 ----------

  private CostRunCostItemServiceImpl build(
      ProductPropertyMapper productPropertyMapper, boolean includeWaterPowerInMaterial) {
    return new CostRunCostItemServiceImpl(
        mock(CostRunCostItemMapper.class),
        mock(OaFormMapper.class),
        mock(OaFormItemMapper.class),
        mock(SalaryCostMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(AuxCostItemMapper.class),
        mock(CostRunPartItemMapper.class),
        mock(QualityLossRateMapper.class),
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        productPropertyMapper,
        includeWaterPowerInMaterial);
  }
}
