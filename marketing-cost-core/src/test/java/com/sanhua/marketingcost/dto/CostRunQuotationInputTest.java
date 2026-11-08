package com.sanhua.marketingcost.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CostRunQuotationInputTest {
  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
  void anotherConsumerCannotUseTheSelectedQuotation(int change) {
    var input = input(); var context = context();
    switch (change) {
      case 0 -> context.setOaNo("OA-B");
      case 1 -> context.setOaFormItemId(3L);
      case 2 -> context.setProductCode("P-2");
      case 3 -> context.setBusinessUnitType("HOUSEHOLD");
      case 4 -> context.setOaFormItemId(null);
      default -> throw new AssertionError();
    }
    assertThatThrownBy(() -> input.requireFor(context, "OA-A", "P-1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void inputIsDetachedAndInternalQuotationDocumentIsNotDuplicatedInSerializedContext() throws Exception {
    var form = input().form(); var item = input().item();
    form.setCopperPrice(new BigDecimal("80000.123456")); item.setAnnualVolume(new BigDecimal("123.456"));
    var selected = new CostRunQuotationInput(form, item);
    form.setCopperPrice(BigDecimal.ZERO); item.setAnnualVolume(BigDecimal.ZERO);
    selected.form().setCopperPrice(BigDecimal.ONE); selected.item().setAnnualVolume(BigDecimal.ONE);
    assertThat(selected.form().getCopperPrice()).isEqualByComparingTo("80000.123456");
    assertThat(selected.item().getAnnualVolume()).isEqualByComparingTo("123.456");
    var context = context(); context.setQuotationInput(selected);
    selected.requireFor(context, "OA-A", "P-1");
    assertThat(new ObjectMapper().writeValueAsString(context)).doesNotContain("quotationInput", "80000.123456");
  }

  @Test void mismatchedHeaderAndItemAreRejected() {
    var form = input().form(); var item = input().item(); item.setOaFormId(99L);
    assertThatThrownBy(() -> new CostRunQuotationInput(form, item)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void newProductKeepsExistingSyntheticIdentityAndHeaderUnitFallback() {
    var form = input().form(); var item = input().item();
    item.setMaterialNo(null); item.setSunlModel("NEW-Model"); item.setBusinessUnitType(null);
    var selected = new CostRunQuotationInput(form, item); var context = context();
    String code = QuoteProductIdentityUtils.resolveCostingCode(item); context.setProductCode(code);
    selected.requireFor(context, "OA-A", code);
  }

  private static CostRunQuotationInput input() {
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA-A"); form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem(); item.setId(2L); item.setOaFormId(1L); item.setMaterialNo("P-1");
    item.setBusinessUnitType("COMMERCIAL"); return new CostRunQuotationInput(form, item);
  }

  private static CostRunContext context() {
    return CostRunContext.monthlyReprice("2026-11", "MRP-A", "COMMERCIAL", "OA-A", 2L, "P-1", null, null, "OBJECT-A");
  }
}
