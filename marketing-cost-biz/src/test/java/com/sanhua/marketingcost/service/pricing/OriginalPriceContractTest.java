package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginalPriceContractTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"2026-1","26-10","2026-13","2026-00","2026-10-01","  "})
  void rejectsMissingOrInvalidMonth(String month) {
    assertThatThrownBy(() -> new FactorPriceScope("COMMERCIAL","210",month,"CU_90",Condition.GENERAL))
        .isInstanceOf(RuntimeException.class);
  }

  @Test void doesNotDeriveOrganizationFromBusinessUnitOrUseAnImplicitScheme() {
    assertThatThrownBy(() -> new FactorPriceScope("COMMERCIAL",null,"2026-10","CU_90",Condition.GENERAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FactorPriceScope("PLATE","220","2026-10","BOARD",Condition.GENERAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FactorPriceScope("COMMERCIAL","220","2026-10","LEGACY",Condition.TENDER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FactorPriceScope("COMMERCIAL","210","2026-10","CU_90",Condition.TENDER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new FactorPriceScope(" commercial ","220","2026-10","board",Condition.TENDER).schemeCode())
        .isEqualTo("BOARD");
  }

  @Test void supplierCodeIsStableAcrossDisplayNameChangesButAllPriceDimensionsRemainDistinct() {
    var key = key("210","S1","名称一","304","只");
    assertThat(key.identityKey()).isEqualTo(key("210","S1","名称二","304","只").identityKey());
    assertThat(key.identityKey()).isNotEqualTo(key("210","S2","名称一","304","只").identityKey());
    assertThat(key.identityKey()).isNotEqualTo(key("220","S1","名称一","304","只").identityKey());
    assertThat(key.identityKey()).isNotEqualTo(key("210","S1","名称一","316","只").identityKey());
    assertThat(key.identityKey()).isNotEqualTo(key("210","S1","名称一","304","米").identityKey());
    assertThat(key.identityKey()).hasSize(64);
  }

  @Test void supplierNamesAreUsedOnlyWhenCodeIsMissingAndNeverFuzzilyMatched() {
    assertThat(key("210",null,"供应商甲","","只").identityKey())
        .isNotEqualTo(key("210",null,"供应商乙","","只").identityKey());
    assertThat(key("210","供应商甲",null,"","只").identityKey())
        .isNotEqualTo(key("210",null,"供应商甲","","只").identityKey());
    assertThatThrownBy(() -> key("210",null,null,"","只"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("供应商");
    assertThat(key("210","A|B","","C","只").identityKey())
        .isNotEqualTo(key("210","A","","B|C","只").identityKey());
  }

  private static MaterialPriceRuleKey key(String org, String code, String name, String spec, String unit) {
    return new MaterialPriceRuleKey("COMMERCIAL",org,"M1",code,name,spec,unit);
  }
}
