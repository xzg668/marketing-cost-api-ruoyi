package com.sanhua.marketingcost.service.effectivebom;

import java.util.List;

/** 规范化节点、排除摘要和阻断问题组成的纯构建结果。 */
public record EffectiveBomBuildResult(
    List<EffectiveBomNodeDraft> nodes,
    List<EffectiveBomExclusion> exclusions,
    List<EffectiveBomBlockIssue> blockIssues,
    List<String> warnings) {

  public EffectiveBomBuildResult {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    blockIssues = blockIssues == null ? List.of() : List.copyOf(blockIssues);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public boolean blocked() {
    return !blockIssues.isEmpty();
  }
}
