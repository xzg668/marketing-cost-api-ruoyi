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

class QuoteEffectiveBomManufactureExpandTest {

  @Test
  void purchaseChangedToManufactureExpandsAvailableChildren() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1", "采购件"),
                        node(3, "B", "A", 2, "/P/A/B/", "2", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    fixed("A", "采购件", QuoteMaterialShape.MANUFACTURE),
                    shape("B", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).containsExactly("P", "A", "B");
  }

  @Test
  void reachableManufactureNodeWithoutChildrenCreatesBomGap() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    fixed("A", "采购件", QuoteMaterialShape.MANUFACTURE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anySatisfy(issue -> {
          assertThat(issue.issueCode()).isEqualTo("BOM_GAP");
          assertThat(issue.materialCode()).isEqualTo("A");
        });
  }
}
