package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.actionJson;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.supplierDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierRatioShapePolicyActionTest {

  private final EffectiveBomPolicyActionResolver resolver =
      new EffectiveBomPolicyActionResolver(new ObjectMapper());

  @Test
  void parsesConfiguredDirectChildCodesWithoutKnowingBusinessMaterial() {
    EffectiveBomShapeDecision decision =
        supplierDecision(
            "GENERIC-PARENT",
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            actionJson("GENERIC-CHILD"));

    EffectiveBomPolicyAction action = resolver.resolve(decision);

    assertThat(action.excludedDirectChildMaterialCodes())
        .containsExactly("GENERIC-CHILD");
    assertThat(decision.actionConfigJson()).contains("GENERIC-CHILD");
  }

  @Test
  void genericParentUsesSameConfiguredExclusionBehavior() {
    EffectiveBomShapeDecision parentDecision =
        supplierDecision(
            "GENERIC-PARENT",
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            actionJson("GENERIC-CHILD"));
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "GENERIC-PARENT", "P", 1, "/P/GENERIC-PARENT/", "1", "委外加工件"),
                        node(3, "GENERIC-CHILD", "GENERIC-PARENT", 2, "/P/GENERIC-PARENT/GENERIC-CHILD/", "1", "采购件"),
                        node(4, "KEEP-CHILD", "GENERIC-PARENT", 2, "/P/GENERIC-PARENT/KEEP-CHILD/", "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    parentDecision,
                    shape("GENERIC-CHILD", QuoteMaterialShape.PURCHASE),
                    shape("KEEP-CHILD", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isFalse();
    assertThat(result.nodes()).extracting(EffectiveBomNodeDraft::materialCode)
        .containsExactly("P", "GENERIC-PARENT", "KEEP-CHILD");
  }

  @Test
  void malformedConfiguredChildArrayFailsClosed() {
    EffectiveBomShapeDecision decision =
        supplierDecision(
            "GENERIC-PARENT",
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            "{\"excludedDirectChildMaterialCodes\":\"BAD\"}");

    assertThatThrownBy(() -> resolver.resolve(decision))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("excludedDirectChildMaterialCodes");
  }

  @Test
  void malformedOutsourceActionBlocksEffectiveTree() {
    EffectiveBomShapeDecision parentDecision =
        supplierDecision(
            "GENERIC-PARENT",
            QuoteMaterialShape.OUTSOURCE,
            "SUP-EXT",
            false,
            "{\"excludedDirectChildMaterialCodes\":\"BAD\"}");

    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(
                            2,
                            "GENERIC-PARENT",
                            "P",
                            1,
                            "/P/GENERIC-PARENT/",
                            "1",
                            "委外加工件"),
                        node(
                            3,
                            "GENERIC-CHILD",
                            "GENERIC-PARENT",
                            2,
                            "/P/GENERIC-PARENT/GENERIC-CHILD/",
                            "1",
                            "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    parentDecision,
                    shape("GENERIC-CHILD", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> "POLICY_ACTION_INVALID".equals(issue.issueCode()));
  }
}
