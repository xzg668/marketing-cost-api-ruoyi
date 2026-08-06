package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import org.junit.jupiter.api.Test;

class QuoteBomReuseKeyRegressionTest {
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver();

  @Test
  void reuseKeyComesOnlyFromResolvedBusinessDimensions() {
    QuoteBomContext context = resolver.resolve(form("OA-KEY-001", null), item("COMMERCIAL"));

    QuoteBomReuseKey key = QuoteBomReuseKey.from(context);

    assertThat(key.getProductCode()).isEqualTo("MAT-KEY-1");
    assertThat(key.getCustomerCode()).isEqualTo("客户甲");
    assertThat(key.getPackageMethod()).isEqualTo("BOX");
    assertThat(key.getCostPeriodMonth()).isEqualTo("2026-08");
  }

  @Test
  void organizationAndBusinessUnitAreTechnicalIsolationNotPartOfBusinessKey() {
    QuoteBomContext commercial = resolver.resolve(form("OA-KEY-002", null), item("COMMERCIAL"));
    QuoteBomContext plate =
        resolver.resolve(form("FI-SC-020-20260803-002", "FI-SC-020"), item("COMMERCIAL"));

    assertThat(commercial.organization().priceOrgCode()).isEqualTo("210");
    assertThat(plate.organization().priceOrgCode()).isEqualTo("220");
    assertThat(QuoteBomReuseKey.from(commercial)).isEqualTo(QuoteBomReuseKey.from(plate));
  }

  @Test
  void normalizedPackageValuesGenerateEqualKeys() {
    QuoteBomContext nullPackage = resolver.resolve(form("OA-KEY-003", null), item("COMMERCIAL", null));
    QuoteBomContext slashPackage = resolver.resolve(form("OA-KEY-003", null), item("COMMERCIAL", " / "));

    assertThat(QuoteBomReuseKey.from(nullPackage)).isEqualTo(QuoteBomReuseKey.from(slashPackage));
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
    return item(organization, "BOX");
  }

  private OaFormItem item(String organization, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("MAT-KEY-1");
    item.setPackageMethod(packageMethod);
    item.setBusinessUnitType(organization);
    return item;
  }
}
