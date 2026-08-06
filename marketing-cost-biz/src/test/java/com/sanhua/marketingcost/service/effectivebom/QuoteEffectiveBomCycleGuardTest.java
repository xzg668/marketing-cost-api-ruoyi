package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.builder;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomCycleGuardTest {

  @Test
  void repeatedMaterialInsideItsOwnAncestorChainBlocksAsCycle() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1", "制造件"),
                        node(3, "P", "A", 2, "/P/A/P/", "1", "制造件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("A", QuoteMaterialShape.MANUFACTURE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> "BOM_CYCLE".equals(issue.issueCode()));
  }

  @Test
  void nodeBeyondConfiguredMaximumDepthBlocks() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "A", "P", 1, "/P/A/", "1", "制造件"),
                        node(3, "B", "A", 2, "/P/A/B/", "1", "制造件"),
                        node(4, "C", "B", 3, "/P/A/B/C/", "1", "采购件")),
                    List.of(),
                    Map.of(),
                    2,
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("A", QuoteMaterialShape.MANUFACTURE),
                    shape("B", QuoteMaterialShape.MANUFACTURE),
                    shape("C", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> "MAX_DEPTH_EXCEEDED".equals(issue.issueCode()));
  }

  @Test
  void brokenParentPathIsBlockingInsteadOfProducingOrphan() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(
                        node(1, "P", "P", 0, "/P/", "1", "制造件"),
                        node(2, "C", "MISSING", 2, "/P/MISSING/C/", "1", "采购件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE),
                    shape("C", QuoteMaterialShape.PURCHASE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> "PARENT_PATH_MISSING".equals(issue.issueCode()));
  }

  @Test
  void missingNodePathReturnsStableBlockingIssue() {
    EffectiveBomBuildResult result =
        builder()
            .build(
                request(
                    List.of(node(1, "P", "P", 0, null, "1", "制造件")),
                    shape("P", QuoteMaterialShape.MANUFACTURE)));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockIssues())
        .anyMatch(issue -> "PATH_MISSING".equals(issue.issueCode()));
  }
}
