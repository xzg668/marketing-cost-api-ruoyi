package com.sanhua.marketingcost.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaterialOrganization")
class MaterialOrganizationTest {

  @Test
  @DisplayName("FI-SC-020 报价流程识别为板换，其它流程默认商用")
  void quoteProcessResolvesPlateOnlyForFiSc020() {
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-020", null)).isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess(null, "FI-SC-020-20260616-001"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess(null, "FISC020-20260616-001"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null)).isEqualTo("COMMERCIAL");
    assertThat(MaterialOrganization.forQuoteProcess(null, "1001900001090")).isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("非 FI-SC-020 报价按产品名称识别板换组织")
  void quoteProductNameResolvesPlateForPlateKeywords() {
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null, "板换组件"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null, "商用换热水器"))
        .isEqualTo("PLATE");
    assertThat(MaterialOrganization.forQuoteProcess("FI-SC-006", null, "普通阀件"))
        .isEqualTo("COMMERCIAL");
  }
}
