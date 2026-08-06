package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;

/** 单分支 BOM 节点、处理统计和非阻断告警。 */
public record BomAlternativePruneResult(
    List<BomRawHierarchy> nodes,
    int inputNodeCount,
    int outputNodeCount,
    int removedNodeCount,
    int processedGroupCount,
    int skippedGroupCount,
    List<String> processedGroupKeys,
    List<String> skippedGroupKeys,
    List<String> warnings) {

  public BomAlternativePruneResult {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    processedGroupKeys =
        processedGroupKeys == null
            ? List.of()
            : List.copyOf(processedGroupKeys);
    skippedGroupKeys =
        skippedGroupKeys == null ? List.of() : List.copyOf(skippedGroupKeys);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
