package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class QuoteBomReuseKeyTest {
  private static final Clock JUNE_CLOCK =
      Clock.fixed(Instant.parse("2026-06-01T00:01:00Z"), ZoneId.of("UTC"));

  @Test
  void itemCustomerTakesPrecedenceOverHeaderCustomer() {
    OaForm form = form(" HEADER-CUST ");
    OaFormItem item = item(" MAT-1001 ", " ITEM-CUST ", " BOX ");

    QuoteBomReuseKey key = QuoteBomReuseKey.from(form, item, JUNE_CLOCK);

    assertThat(key.getProductCode()).isEqualTo("MAT-1001");
    assertThat(key.getCustomerCode()).isEqualTo("ITEM-CUST");
    assertThat(key.getPackageMethod()).isEqualTo("BOX");
    assertThat(key.getCostPeriodMonth()).isEqualTo("2026-06");
  }

  @Test
  void headerCustomerUsedWhenItemCustomerMissing() {
    QuoteBomReuseKey key = QuoteBomReuseKey.from(form(" HEADER-CUST "), item("MAT-1002", " ", "BOX"), JUNE_CLOCK);

    assertThat(key.getCustomerCode()).isEqualTo("HEADER-CUST");
  }

  @Test
  void blankCustomerAndPackageMethodNormalizeToEmptyString() {
    QuoteBomReuseKey key = QuoteBomReuseKey.from(form(" / "), item("MAT-1003", null, " / "), JUNE_CLOCK);

    assertThat(key.getCustomerCode()).isEmpty();
    assertThat(key.getPackageMethod()).isEmpty();
  }

  @Test
  void systemDateGeneratesCurrentCostPeriodMonth() {
    QuoteBomReuseKey key = QuoteBomReuseKey.from(form("CUST"), item("MAT-1004", null, null), JUNE_CLOCK);

    assertThat(key.getCostPeriodMonth()).isEqualTo("2026-06");
  }

  @Test
  void productCodeIsRequired() {
    assertThatThrownBy(() -> QuoteBomReuseKey.from(form("CUST"), item(" ", "CUST", "BOX"), JUNE_CLOCK))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("产品料号不能为空");
  }

  private OaForm form(String customer) {
    OaForm form = new OaForm();
    form.setCustomer(customer);
    return form;
  }

  private OaFormItem item(String materialNo, String customerCode, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo(materialNo);
    item.setCustomerCode(customerCode);
    item.setPackageMethod(packageMethod);
    return item;
  }
}
