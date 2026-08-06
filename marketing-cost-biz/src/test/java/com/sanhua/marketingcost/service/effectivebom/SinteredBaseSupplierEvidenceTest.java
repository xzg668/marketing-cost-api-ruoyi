package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.BASE;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.GOLD;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.actionJson;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.decisions;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.standardTree;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.supplierDecision;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SinteredBaseSupplierEvidenceTest {

  @Test
  void finalParentNodeKeepsRuleAndSupplierEvidence() {
    EffectiveBomShapeDecision baseDecision =
        supplierDecision(
            BASE,
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            actionJson(GOLD));

    EffectiveBomBuildResult result =
        builder().build(request(standardTree(), decisions(baseDecision)));

    assertThat(result.nodes()).filteredOn(node -> BASE.equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> {
          assertThat(node.shapePolicyId()).isEqualTo(701L);
          assertThat(node.shapePolicyFingerprint()).isEqualTo("supplier-policy-fingerprint");
          assertThat(node.selectedSupplierRatioId()).isEqualTo(801L);
          assertThat(node.selectedSupplierCode()).isEqualTo("SUP-EXT");
          assertThat(node.selectedSupplyRatio()).isEqualByComparingTo("0.60");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"最大供货比例并列", "最大比例记录缺少供应商编码"})
  void unresolvedSupplierEvidenceBlocksTree(String reason) {
    EffectiveBomShapeDecision blocked =
        EffectiveBomShapeDecision.blocked(BASE, reason);

    EffectiveBomBuildResult result =
        builder().build(request(standardTree(), decisions(blocked)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> issue.message().contains(reason));
  }

  @Test
  void noSupplyRatioFallbackKeepsU9ShapeAndOriginalChildren() {
    EffectiveBomShapeDecision fallback =
        new EffectiveBomShapeDecision(
            BASE,
            "制造件",
            QuoteMaterialShape.MANUFACTURE,
            MaterialQuoteShapeSource.SUPPLIER_RATIO,
            701L,
            "supplier-policy-fingerprint",
            null,
            null,
            null,
            null,
            null,
            null);

    EffectiveBomBuildResult result =
        builder().build(request(standardTree(), decisions(fallback)));

    assertThat(result.blocked()).isFalse();
    assertThat(result.nodes())
        .extracting(EffectiveBomNodeDraft::materialCode)
        .contains(BASE, GOLD);
    assertThat(result.exclusions())
        .noneMatch(exclusion -> "POLICY_DIRECT_CHILD_EXCLUSION".equals(exclusion.reasonCode()));
  }
}
