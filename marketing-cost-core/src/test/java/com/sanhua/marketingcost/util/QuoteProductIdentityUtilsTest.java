package com.sanhua.marketingcost.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaFormItem;
import org.junit.jupiter.api.Test;

class QuoteProductIdentityUtilsTest {

  @Test
  void formalMaterialWinsOverModelAndDrawing() {
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(" MAT-1 ", "MODEL-1", "DRW-1"))
        .isEqualTo("MAT-1");
  }

  @Test
  void modelAndDrawingCanProvideStableCostingIdentity() {
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(null, " model-1 ", "DRW-1"))
        .isEqualTo("MODEL:MODEL-1");
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(null, null, " drw-1 "))
        .isEqualTo("DRAWING:DRW-1");
  }

  @Test
  void noIdentityReturnsNullAndLongIdentityStillFitsDatabaseColumns() {
    OaFormItem item = new OaFormItem();
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(item)).isNull();

    String code = QuoteProductIdentityUtils.resolveCostingCode(null, "M".repeat(100), null);
    assertThat(code).startsWith("MODEL#").hasSize(64);
  }
  @Test void specificationAloneProvidesStableIdentityWithoutChangingExistingKeys() {
    OaFormItem item = new OaFormItem(); item.setSpec(" 160hz 板换 ");
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(item)).isEqualTo("SPEC:160HZ 板换");
    item.setCustomerDrawing("D-1");
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(item)).isEqualTo("DRAWING:D-1");
    item.setSunlModel("M-1");
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(item)).isEqualTo("MODEL:M-1");
    item.setMaterialNo("P-1");
    assertThat(QuoteProductIdentityUtils.resolveCostingCode(item)).isEqualTo("P-1");
  }
}
