package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.BASE;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.GOLD;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.actionJson;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.supplierDecision;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class SinteredBaseDirectChildExclusionTest {

  @Test
  void configuredMaterialBelowAnIntermediateNodeIsNotMistakenForDirectChild() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, BASE, "P", 1, "/P/" + BASE + "/", "1", "制造件"),
                        node(3, "MID", BASE, 2, "/P/" + BASE + "/MID/", "1", "制造件"),
                        node(4, GOLD, "MID", 3, "/P/" + BASE + "/MID/" + GOLD + "/", "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    supplierDecision(BASE, QuoteMaterialShape.OUTSOURCE, "SUP-EXT", false, actionJson(GOLD)),
                    shape("MID", QuoteMaterialShape.MANUFACTURE),
                    shape(GOLD, QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(result.nodes()).extracting(EffectiveBomNodeDraft::materialCode)
        .contains(GOLD);
    assertThat(result.exclusions()).isEmpty();
  }

  @Test
  void sameGoldMaterialUnderAnotherParentRemains() {
    String baseGoldPath = "/P/" + BASE + "/" + GOLD + "/";
    String otherGoldPath = "/P/OTHER/" + GOLD + "/";
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, BASE, "P", 1, "/P/" + BASE + "/", "1", "制造件"),
                        node(3, GOLD, BASE, 2, baseGoldPath, "1", "采购件"),
                        node(4, "OTHER", "P", 1, "/P/OTHER/", "1", "制造件"),
                        node(5, GOLD, "OTHER", 2, otherGoldPath, "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    supplierDecision(BASE, QuoteMaterialShape.OUTSOURCE, "SUP-EXT", false, actionJson(GOLD)),
                    shape("OTHER", QuoteMaterialShape.MANUFACTURE),
                    shape(GOLD, QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(result.nodes()).filteredOn(node -> GOLD.equals(node.materialCode()))
        .singleElement()
        .satisfies(node -> assertThat(node.nodePath()).isEqualTo(otherGoldPath));
  }
}
