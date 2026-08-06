package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.alternativeGroup;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.alternativeNode;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.materialCodes;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomAlternativeOrderTest {

  @Test
  void missingManualSelectionDefaultsToStandardBeforeShapeValidation() {
    BomRawHierarchy standard =
        alternativeNode(2, "S", 1, "/P/S/", "STANDARD", "G1");
    BomRawHierarchy standardChild =
        alternativeNode(3, "S1", 2, "/P/S/S1/", "NORMAL", null);
    BomRawHierarchy alternative =
        alternativeNode(4, "T", 1, "/P/T/", "ALTERNATIVE", "G1");
    BomRawHierarchy invalidUnselectedChild =
        alternativeNode(5, "T1", 2, "/P/T/T1/", "NORMAL", null);
    BomAlternativeGroup group = alternativeGroup("G1", standard, alternative);

    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        invalidUnselectedChild,
                        alternative,
                        standardChild,
                        standard),
                    List.of(group),
                    Map.of(),
                    128,
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("S", QuoteMaterialShape.MANUFACTURE),
                    shape("S1", QuoteMaterialShape.PURCHASE),
                    EffectiveBomShapeDecision.blocked("T", "未选分支故意异常"),
                    EffectiveBomShapeDecision.blocked("T1", "未选分支故意异常")));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).containsExactly("P", "S", "S1");
    assertThat(result.exclusions())
        .anyMatch(exclusion -> "ALTERNATIVE_UNSELECTED".equals(exclusion.reasonCode()));
  }

  @Test
  void manualAlternativeSelectionKeepsAlternativeWholeSubtree() {
    BomRawHierarchy standard =
        alternativeNode(2, "S", 1, "/P/S/", "STANDARD", "G1");
    BomRawHierarchy standardChild =
        alternativeNode(3, "S1", 2, "/P/S/S1/", "NORMAL", null);
    BomRawHierarchy alternative =
        alternativeNode(4, "T", 1, "/P/T/", "ALTERNATIVE", "G1");
    BomRawHierarchy alternativeChild =
        alternativeNode(5, "T1", 2, "/P/T/T1/", "NORMAL", null);

    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        standard,
                        standardChild,
                        alternative,
                        alternativeChild),
                    List.of(alternativeGroup("G1", standard, alternative)),
                    Map.of("G1", "T"),
                    128,
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("T", QuoteMaterialShape.MANUFACTURE),
                    shape("T1", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).containsExactly("P", "T", "T1");
  }
}
