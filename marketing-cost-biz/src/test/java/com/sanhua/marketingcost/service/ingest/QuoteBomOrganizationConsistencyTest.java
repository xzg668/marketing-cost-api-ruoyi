package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import org.junit.jupiter.api.Test;

class QuoteBomOrganizationConsistencyTest {
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver();

  @Test
  void commercialContextResolvesPriceOrganization210() {
    QuoteBomContext context = resolver.resolve(form("OA-ORG-001", null), item("COMMERCIAL"));

    assertThat(context.organization().priceOrgCode()).isEqualTo("210");
    assertThat(context.organization().materialOrganizationCode()).isEqualTo("COMMERCIAL");
  }

  @Test
  void plateProcessResolvesPriceOrganization220() {
    QuoteBomContext context =
        resolver.resolve(form("FI-SC-020-20260803-001", "FI-SC-020"), item("COMMERCIAL"));

    assertThat(context.organization().priceOrgCode()).isEqualTo("220");
    assertThat(context.organization().materialOrganizationCode()).isEqualTo("PLATE");
  }

  @Test
  void sourceOrganizationSameAsResolvedOrganizationIsAccepted() {
    QuoteBomContext context = resolver.resolve(form("OA-ORG-002", null), item("COMMERCIAL"));

    assertThatCode(() -> resolver.validateSourceOrganization(context, " 210 "))
        .doesNotThrowAnyException();
  }

  @Test
  void sourceOrganizationDifferentFromResolvedOrganizationIsBlocked() {
    QuoteBomContext context = resolver.resolve(form("OA-ORG-003", null), item("COMMERCIAL"));

    assertThatThrownBy(() -> resolver.validateSourceOrganization(context, "220"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("210")
        .hasMessageContaining("220")
        .hasMessageContaining("不一致");
  }

  private OaForm form(String oaNo, String processCode) {
    OaForm form = new OaForm();
    form.setOaNo(oaNo);
    form.setProcessCode(processCode);
    form.setCustomer("客户甲");
    form.setAccountingPeriodMonth("2026-08");
    return form;
  }

  private OaFormItem item(String organization) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("MAT-ORG-1");
    item.setBusinessUnitType(organization);
    return item;
  }
}
