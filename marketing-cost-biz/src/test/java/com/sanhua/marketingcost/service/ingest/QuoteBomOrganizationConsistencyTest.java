package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteBomOrganizationConsistencyTest {
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver(materials);

  @Test
  void missingOaNameUsesUnambiguousCurrentMaterialNameWithoutChangingSourceFields() {
    var item = item("COMMERCIAL");
    item.setProductName(null);
    item.setMaterialNo("1053900000078");
    item.setSunlModel("S11AH-60L-04");
    when(materials.selectActiveNamesForQuoteOrganization("1053900000078"))
        .thenReturn(List.of("钎焊板式换热器", " 钎焊板式换热器 "));
    var context = resolver.resolve(form("FI-SC-006-20260108-109", "FI-SC-006"), item);
    assertThat(context.organization().priceOrgCode()).isEqualTo("220");
    assertThat(context.organization().materialOrganizationCode()).isEqualTo("PLATE");
    assertThat(item.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(item.getProductName()).isNull();
  }

  @Test
  void ordinaryCommercialMaterialStillUses210WhenOaNameIsMissing() {
    var item = item("COMMERCIAL");
    item.setProductName(" / ");
    when(materials.selectActiveNamesForQuoteOrganization("MAT-ORG-1")).thenReturn(List.of("电磁阀阀体"));
    assertThat(resolver.resolveOrganization(form("OA-1", null), item).priceOrgCode()).isEqualTo("210");
  }

  @Test
  void explicitOaNameAndPlateProcessDoNotDependOnMaterialMaster() {
    assertThat(resolver.resolveOrganization(form("OA-1", null), item("COMMERCIAL")).priceOrgCode()).isEqualTo("210");
    var unnamed = item("COMMERCIAL");
    unnamed.setProductName(null);
    assertThat(resolver.resolveOrganization(form("FI-SC-020-1", "FI-SC-020"), unnamed).priceOrgCode()).isEqualTo("220");
    verifyNoInteractions(materials);
  }

  @Test
  void absentOrConflictingMasterNamesAreBlockedInsteadOfGuessingCommercial() {
    var item = item("COMMERCIAL");
    item.setProductName(null);
    when(materials.selectActiveNamesForQuoteOrganization("MAT-ORG-1")).thenReturn(List.of(" ", "/"));
    assertThatThrownBy(() -> resolver.resolveOrganization(form("OA-1", null), item))
        .isInstanceOf(QuoteIngestException.class).hasMessageContaining("MAT-ORG-1").hasMessageContaining("无有效名称");
    when(materials.selectActiveNamesForQuoteOrganization("MAT-ORG-1")).thenReturn(List.of("电磁阀", "钎焊板式换热器"));
    assertThatThrownBy(() -> resolver.resolveOrganization(form("OA-1", null), item))
        .isInstanceOf(QuoteIngestException.class).hasMessageContaining("名称不一致");
  }

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
    item.setProductName("电磁阀");
    item.setBusinessUnitType(organization);
    return item;
  }
}
