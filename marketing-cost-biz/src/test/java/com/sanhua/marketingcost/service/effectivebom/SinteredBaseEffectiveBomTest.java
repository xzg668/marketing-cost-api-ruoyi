package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.materialCodes;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.BASE;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.BLANK;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.GOLD;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.actionJson;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.decisions;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.standardTree;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.supplierDecision;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SinteredBaseEffectiveBomTest {

  @ParameterizedTest
  @ValueSource(strings = {"SUP-210", "SUP-220"})
  void internalMainSupplierKeepsManufactureShapeAndGold(String supplierCode) {
    EffectiveBomShapeDecision baseDecision =
        supplierDecision(
            BASE,
            QuoteMaterialShape.MANUFACTURE,
            supplierCode,
            true,
            actionJson(GOLD));

    EffectiveBomBuildResult result =
        builder().build(request(standardTree(), decisions(baseDecision)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).contains(BASE, BLANK, GOLD);
    assertThat(result.nodes()).filteredOn(node -> BASE.equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> assertThat(node.effectiveMaterialShape())
            .isEqualTo(QuoteMaterialShape.MANUFACTURE));
  }

  @Test
  void externalMainSupplierUsesOutsourceAndRemovesGoldWholeSubtree() {
    EffectiveBomShapeDecision baseDecision =
        supplierDecision(
            BASE,
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            actionJson(GOLD));

    EffectiveBomBuildResult result =
        builder().build(request(standardTree(), decisions(baseDecision)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).contains(BASE, BLANK).doesNotContain(GOLD, "GOLD-CHILD");
    assertThat(result.exclusions()).singleElement().satisfies(exclusion -> {
      assertThat(exclusion.reasonCode()).isEqualTo("POLICY_DIRECT_CHILD_EXCLUSION");
      assertThat(exclusion.triggerMaterialCode()).isEqualTo(BASE);
      assertThat(exclusion.excludedRootMaterialCode()).isEqualTo(GOLD);
      assertThat(exclusion.excludedNodeCount()).isEqualTo(2);
    });
  }
}
