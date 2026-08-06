package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import org.junit.jupiter.api.Test;

class QuoteBomReuseKeyTest {
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver();

  @Test
  void itemCustomerMaterialNumberNeverTakesPrecedenceOverHeaderCustomer() {
    OaForm form = form(" HEADER-CUST ");
    OaFormItem item = item(" MAT-1001 ", " ITEM-CUST ", " BOX ");

    QuoteBomReuseKey key = QuoteBomReuseKey.from(resolver.resolve(form, item));

    assertThat(key.getProductCode()).isEqualTo("MAT-1001");
    assertThat(key.getCustomerCode()).isEqualTo("HEADER-CUST");
    assertThat(key.getPackageMethod()).isEqualTo("BOX");
    assertThat(key.getCostPeriodMonth()).isEqualTo("2026-08");
  }

  @Test
  void headerCustomerUsedWhenItemCustomerMissing() {
    QuoteBomReuseKey key =
        QuoteBomReuseKey.from(
            resolver.resolve(form(" HEADER-CUST "), item("MAT-1002", " ", "BOX")));

    assertThat(key.getCustomerCode()).isEqualTo("HEADER-CUST");
  }

  @Test
  void blankCustomerFallsBackToOaAndPackageMethodNormalizesToEmptyString() {
    QuoteBomReuseKey key =
        QuoteBomReuseKey.from(resolver.resolve(form(" / "), item("MAT-1003", null, " / ")));

    assertThat(key.getCustomerCode()).isEqualTo("OA:OA-KEY-001");
    assertThat(key.getPackageMethod()).isEmpty();
  }

  @Test
  void oaAccountingMonthGeneratesCostPeriodMonth() {
    QuoteBomReuseKey key =
        QuoteBomReuseKey.from(resolver.resolve(form("CUST"), item("MAT-1004", null, null)));

    assertThat(key.getCostPeriodMonth()).isEqualTo("2026-08");
  }

  @Test
  void productCodeIsRequired() {
    assertThatThrownBy(
            () ->
                QuoteBomReuseKey.from(
                    resolver.resolve(form("CUST"), item(" ", "CUST", "BOX"))))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("产品料号为空");
  }

  private OaForm form(String customer) {
    OaForm form = new OaForm();
    form.setOaNo("OA-KEY-001");
    form.setCustomer(customer);
    form.setAccountingPeriodMonth("2026-08");
    return form;
  }

  private OaFormItem item(String materialNo, String customerCode, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo(materialNo);
    item.setCustomerCode(customerCode);
    item.setPackageMethod(packageMethod);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }
}
