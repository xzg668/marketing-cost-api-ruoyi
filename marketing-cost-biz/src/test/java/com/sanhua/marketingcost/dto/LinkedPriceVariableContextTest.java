package com.sanhua.marketingcost.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaForm;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LinkedPriceVariableContextTest {
  @Test
  void customerScenarioOnlyPreloadsCopper() {
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("100000"));
    form.setZincPrice(new BigDecimal("99000"));
    form.setAluminumPrice(new BigDecimal("98000"));
    form.setSilverPrice(new BigDecimal("97000"));
    form.setGoldPrice(new BigDecimal("96000"));
    form.setSus304Price(new BigDecimal("26000"));
    form.setSus316lPrice(new BigDecimal("32000"));
    var context = LinkedPriceVariableContext.quote(form);
    assertThat(context.getVariableValues()).containsOnlyKeys("Cu");
    assertThat(context.getVariableValues().get("Cu")).isEqualByComparingTo("100");
  }
}
