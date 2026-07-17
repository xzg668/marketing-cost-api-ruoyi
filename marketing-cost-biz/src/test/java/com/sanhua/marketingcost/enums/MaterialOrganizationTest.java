package com.sanhua.marketingcost.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("MaterialOrganization")
class MaterialOrganizationTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("FI-SC-020 报价流程识别为板换，其它流程必须显式传组织")
  void quoteProcessResolvesPlateOnlyForFiSc020() {
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-020", null)).isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess(null, "FI-SC-020-20260616-001"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess(null, "FISC020-20260616-001"))
        .isEqualTo("PLATE");
    assertThatThrownBy(() -> MaterialOrganization.forQuoteProcess("FI-SC-006", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
    assertThatThrownBy(() -> MaterialOrganization.forQuoteProcess(null, "1001900001090"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
  }

  @Test
  @DisplayName("非 FI-SC-020 报价按明细显式组织识别板换或商用")
  void quoteItemOrganizationResolvesOrg() {
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null, "PLATE"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null, "COMMERCIAL"))
        .isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("报价数据组织同时返回 BOM 组织和料品主档组织")
  void quoteDataOrganizationReturnsPriceOrgAndMaterialOrg() {
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess("FI-SC-006", null, "COMMERCIAL"),
        "210",
        "COMMERCIAL");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess("FI-SC-020", null, null),
        "220",
        "PLATE");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess(null, "FI-SC-020-20260616-001", null),
        "220",
        "PLATE");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess(null, "FISC020-20260616-001", null),
        "220",
        "PLATE");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess(
            "FI-SC-006", "FI-SC-020-20260616-001", null),
        "220",
        "PLATE");
  }

  @Test
  @DisplayName("普通产品名称不参与组织判断，非板换专用流程必须传组织码")
  void quoteDataOrganizationDoesNotGuessByGenericProductName() {
    assertThatThrownBy(
            () ->
                MaterialOrganization.quoteDataForQuoteProduct(
                    "FI-SC-006", null, null, "普通组件", "MODEL", "MAT-001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess("FI-SC-006", null, "PLATE"),
        "220",
        "PLATE");
  }

  @Test
  @DisplayName("板式热交换器产品行即使单据业务单元为 COMMERCIAL，也按板换 220/PLATE 取 BOM")
  void quoteProductTextResolvesPlateOrganization() {
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProduct(
            "FI-SR-005",
            "FI-SR-005-20260318-0397",
            "COMMERCIAL",
            "板式热交换器",
            "S12BH-30L-19",
            "1053900000062"),
        "220",
        "PLATE");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProduct(
            "FI-SR-005",
            "FI-SR-005-20260318-0397",
            "COMMERCIAL",
            "钎焊板式换热器",
            "S12BH-30L-19",
            "1053900000062"),
        "220",
        "PLATE");
  }

  @Test
  @DisplayName("空流程和未知流程不默认商用")
  void quoteDataOrganizationRejectsMissingOrg() {
    assertThatThrownBy(() -> MaterialOrganization.quoteDataForQuoteProcess(null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess("FI-SC-006", null, "COMMERCIAL"),
        "210",
        "COMMERCIAL");
    assertThatThrownBy(
            () -> MaterialOrganization.quoteDataForQuoteProcess("UNKNOWN", "OA-UNKNOWN", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("显式传入料品组织");
  }

  @Test
  @DisplayName("登录业务单元上下文经映射后返回报价数据组织，不直接当作 BOM 组织码")
  void currentBusinessUnitContextMapsToQuoteDataOrganization() {
    TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "pwd");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    QuoteDataOrganization organization =
        MaterialOrganization.quoteDataForCurrentContext(null, null, null);

    assertQuoteData(organization, "210", "COMMERCIAL");
    assertThat(organization.priceOrgCode()).isNotEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("明确板换业务单元上下文返回 220/PLATE")
  void explicitPlateBusinessUnitContextMapsToPlate() {
    assertQuoteData(
        MaterialOrganization.quoteDataForQuoteProcess(null, null, null, "PLATE"),
        "220",
        "PLATE");
  }

  private static void assertQuoteData(
      QuoteDataOrganization actual, String priceOrgCode, String materialOrganizationCode) {
    assertThat(actual.priceOrgCode()).isEqualTo(priceOrgCode);
    assertThat(actual.materialOrganizationCode()).isEqualTo(materialOrganizationCode);
  }
}
