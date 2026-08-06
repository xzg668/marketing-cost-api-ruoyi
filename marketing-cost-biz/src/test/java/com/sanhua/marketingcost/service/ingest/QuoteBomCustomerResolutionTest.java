package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import org.junit.jupiter.api.Test;

class QuoteBomCustomerResolutionTest {
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver();

  @Test
  void verifiedFormalCustomerCodeHasHighestPriority() {
    OaForm form = form("OA-CUST-001", "表头客户名称");
    OaFormItem item = item("客户自己的物料号");

    QuoteBomContext context = resolver.resolve(form, item, " C000123 ");

    assertThat(context.customer().value()).isEqualTo("C000123");
    assertThat(context.customer().source())
        .isEqualTo(ResolvedCustomerKey.Source.VERIFIED_CUSTOMER_CODE);
    assertThat(context.customer().hasWarning()).isFalse();
  }

  @Test
  void itemCustomerCodeIsCustomerMaterialNumberAndNeverCustomerIdentity() {
    OaForm form = form("OA-CUST-002", " 表头客户名称 ");
    OaFormItem item = item(" CUSTOMER-MATERIAL-7788 ");

    QuoteBomContext context = resolver.resolve(form, item);

    assertThat(context.customerKey()).isEqualTo("表头客户名称");
    assertThat(context.customer().source()).isEqualTo(ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER);
  }

  @Test
  void headerCustomerNameIsNormalizedWhenNoVerifiedCodeExists() {
    QuoteBomContext context = resolver.resolve(form("OA-CUST-003", "  客户乙  "), item(null));

    assertThat(context.customerKey()).isEqualTo("客户乙");
    assertThat(context.customer().hasWarning()).isFalse();
  }

  @Test
  void missingCustomerFallsBackToOaIsolationKeyAndWarning() {
    QuoteBomContext context = resolver.resolve(form(" OA-CUST-004 ", " / "), item("CUST-MAT"));

    assertThat(context.customerKey()).isEqualTo("OA:OA-CUST-004");
    assertThat(context.customer().source()).isEqualTo(ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK);
    assertThat(context.customer().hasWarning()).isTrue();
    assertThat(context.customer().warning()).contains("客户").contains("OA");
  }

  @Test
  void differentOasWithoutCustomerNeverReuseTheSameCustomerKey() {
    QuoteBomContext first = resolver.resolve(form("OA-CUST-005", null), item(null));
    QuoteBomContext second = resolver.resolve(form("OA-CUST-006", null), item(null));

    assertThat(first.customerKey()).isNotEqualTo(second.customerKey());
    assertThat(first.customerKey()).isEqualTo("OA:OA-CUST-005");
    assertThat(second.customerKey()).isEqualTo("OA:OA-CUST-006");
  }

  private OaForm form(String oaNo, String customer) {
    OaForm form = new OaForm();
    form.setOaNo(oaNo);
    form.setCustomer(customer);
    form.setAccountingPeriodMonth("2026-08");
    return form;
  }

  private OaFormItem item(String customerMaterialNo) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("MAT-CUST-1");
    item.setCustomerCode(customerMaterialNo);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }
}
