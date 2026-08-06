package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.materialCodes;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomOutsourceTest {

  @Test
  void outsourceNodeKeepsItsChildrenByDefault() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "O", "P", 1, "/P/O/", "1", "委外加工件"),
                        node(3, "B", "O", 2, "/P/O/B/", "2", "采购件"),
                        node(4, "C", "O", 2, "/P/O/C/", "3", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("O", QuoteMaterialShape.OUTSOURCE),
                    shape("B", QuoteMaterialShape.PURCHASE),
                    shape("C", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).containsExactly("P", "O", "B", "C");
    assertThat(result.exclusions())
        .noneMatch(exclusion -> "O".equals(exclusion.triggerMaterialCode()));
  }
}
