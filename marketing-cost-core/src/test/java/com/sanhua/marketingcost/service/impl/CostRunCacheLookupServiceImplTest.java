package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyRuleMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseDimensionMappingMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostRunCacheLookupServiceImplTest {

  @Test
  void derivesCoefficientFromTheAnnualBusinessRule() {
    ProductPropertyMapper propertyMapper = mock(ProductPropertyMapper.class);
    ProductPropertyRuleMapper ruleMapper = mock(ProductPropertyRuleMapper.class);
    ProductProperty property = new ProductProperty();
    property.setBusinessUnitType("COMMERCIAL");
    property.setPropertyYear(2026);
    property.setProductCode("1001000300045");
    property.setProductAttr("非标品");
    ProductPropertyRule rule = new ProductPropertyRule();
    rule.setUpliftRate(new BigDecimal("0.050000"));
    when(propertyMapper.selectOne(any())).thenReturn(property);
    when(ruleMapper.selectOne(any())).thenReturn(rule);

    CostRunCacheLookupServiceImpl service = new CostRunCacheLookupServiceImpl(
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        propertyMapper,
        ruleMapper,
        mock(ThreeExpenseDimensionMappingMapper.class));

    ProductProperty result = service.findProductProperty("1001000300045", 2026, "COMMERCIAL");

    assertThat(result.getUpliftRate()).isEqualByComparingTo("0.05");
    assertThat(result.getCoefficient()).isEqualByComparingTo("1.05");
  }

  @Test
  void leavesCoefficientUnsetWhenTheProductIsNotInTheBusinessList() {
    ProductPropertyMapper propertyMapper = mock(ProductPropertyMapper.class);
    when(propertyMapper.selectOne(any())).thenReturn(null);
    CostRunCacheLookupServiceImpl service = new CostRunCacheLookupServiceImpl(
        mock(ManufactureRateMapper.class),
        mock(ThreeExpenseRateMapper.class),
        mock(DepartmentFundRateMapper.class),
        mock(OtherExpenseRateMapper.class),
        propertyMapper,
        mock(ProductPropertyRuleMapper.class),
        mock(ThreeExpenseDimensionMappingMapper.class));

    assertThat(service.findProductProperty("1001900001090", 2026, "COMMERCIAL")).isNull();
  }
}
