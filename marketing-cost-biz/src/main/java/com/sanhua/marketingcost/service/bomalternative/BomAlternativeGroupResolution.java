package com.sanhua.marketingcost.service.bomalternative;

import java.util.List;

/** 替代组只读识别结果。 */
public record BomAlternativeGroupResolution(
    List<BomAlternativeGroup> groups,
    List<BomAlternativeGroupIssue> issues) {

  public BomAlternativeGroupResolution {
    groups = groups == null ? List.of() : List.copyOf(groups);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  /** 任一结构问题都会阻断对应 BOM 进入后续选择流程。 */
  public boolean hasBlockingIssues() {
    return !issues.isEmpty();
  }
}
