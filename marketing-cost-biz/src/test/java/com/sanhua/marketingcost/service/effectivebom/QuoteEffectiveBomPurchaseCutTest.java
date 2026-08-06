package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.fixed;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.materialCodes;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomPurchaseCutTest {

  @Test
  void manufactureChangedToPurchaseKeepsNodeAndCutsAllDescendants() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1", "制造件"),
                        node(3, "B", "A", 2, "/P/A/B/", "2", "采购件"),
                        node(4, "C", "A", 2, "/P/A/C/", "3", "制造件"),
                        node(5, "D", "C", 3, "/P/A/C/D/", "4", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    fixed("A", "制造件", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).containsExactly("P", "A");
    assertThat(result.exclusions()).singleElement().satisfies(exclusion -> {
      assertThat(exclusion.reasonCode()).isEqualTo("PURCHASE_DESCENDANT_CUT");
      assertThat(exclusion.triggerMaterialCode()).isEqualTo("A");
      assertThat(exclusion.excludedNodeCount()).isEqualTo(3);
    });
  }

  @Test
  void sameMaterialAtTwoOccurrencesIsCutAtBothPaths() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "X", "P", 1, "/P/X/", "1", "制造件"),
                        node(3, "A", "X", 2, "/P/X/A/", "1", "制造件"),
                        node(4, "B", "A", 3, "/P/X/A/B/", "1", "采购件"),
                        node(5, "Y", "P", 1, "/P/Y/", "1", "制造件"),
                        node(6, "A", "Y", 2, "/P/Y/A/", "1", "制造件"),
                        node(7, "C", "A", 3, "/P/Y/A/C/", "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("X", QuoteMaterialShape.MANUFACTURE),
                    shape("Y", QuoteMaterialShape.MANUFACTURE),
                    fixed("A", "制造件", QuoteMaterialShape.PURCHASE)));

    assertThat(result.nodes()).filteredOn(node -> "A".equals(node.materialCode()))
        .hasSize(2);
    assertThat(materialCodes(result)).doesNotContain("B", "C");
    assertThat(result.exclusions()).hasSize(2);
  }
}
