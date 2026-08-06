package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomVirtualPassThroughTest {

  @Test
  void virtualNodeStaysAsEvidenceAndPassesQuantityToChildren() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "V", "P", 1, "/P/V/", "2", "虚拟"),
                        node(3, "C", "V", 2, "/P/V/C/", "3", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("V", QuoteMaterialShape.VIRTUAL),
                    shape("C", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(result.nodes()).filteredOn(node -> "V".equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> assertThat(node.qtyPerTop()).isEqualByComparingTo("2"));
    assertThat(result.nodes()).filteredOn(node -> "C".equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> assertThat(node.qtyPerTop()).isEqualByComparingTo("6"));
  }
}
