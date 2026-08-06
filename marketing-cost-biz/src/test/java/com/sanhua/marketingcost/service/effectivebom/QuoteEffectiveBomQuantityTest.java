package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomQuantityTest {

  @Test
  void qtyPerTopUsesExactBigDecimalMultiplication() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1.23456789", "制造件"),
                        node(3, "B", "A", 2, "/P/A/B/", "0.12345678", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("A", QuoteMaterialShape.MANUFACTURE),
                    shape("B", QuoteMaterialShape.PURCHASE)));

    BigDecimal expected =
        new BigDecimal("1.23456789").multiply(new BigDecimal("0.12345678"));
    assertThat(result.nodes()).filteredOn(node -> "B".equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> assertThat(node.qtyPerTop()).isEqualByComparingTo(expected));
  }

  @Test
  void shuffledInputProducesSameCanonicalOutput() {
    BomRawHierarchy root = node(1, "P", "P", 0, "/P/", "1", "制造件");
    BomRawHierarchy a = node(2, "A", "P", 1, "/P/A/", "2", "采购件");
    BomRawHierarchy b = node(3, "B", "P", 1, "/P/B/", "3", "采购件");

    EffectiveBomBuildResult first =
        builder().build(request(
            List.of(root, a, b),
            shape("P", QuoteMaterialShape.MANUFACTURE),
            shape("A", QuoteMaterialShape.PURCHASE),
            shape("B", QuoteMaterialShape.PURCHASE)));
    EffectiveBomBuildResult shuffled =
        builder().build(request(
            List.of(b, root, a),
            shape("P", QuoteMaterialShape.MANUFACTURE),
            shape("A", QuoteMaterialShape.PURCHASE),
            shape("B", QuoteMaterialShape.PURCHASE)));

    assertThat(shuffled.nodes()).isEqualTo(first.nodes());
    assertThat(shuffled.exclusions()).isEqualTo(first.exclusions());
    assertThat(shuffled.blockIssues()).isEqualTo(first.blockIssues());
  }
}
