package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 原始候选树、替代选择和形态判断组成的不可变构建请求。 */
public record EffectiveBomBuildRequest(
    List<BomRawHierarchy> nodes,
    List<BomAlternativeGroup> alternativeGroups,
    Map<String, String> selectedMaterialCodeByGroupKey,
    Map<String, EffectiveBomShapeDecision> shapeDecisionByMaterialCode,
    int maxDepth) {

  public EffectiveBomBuildRequest {
    nodes = nodes == null ? List.of() : nodes.stream().filter(Objects::nonNull).toList();
    alternativeGroups =
        alternativeGroups == null
            ? List.of()
            : alternativeGroups.stream().filter(Objects::nonNull).toList();
    selectedMaterialCodeByGroupKey = immutableMap(selectedMaterialCodeByGroupKey);
    shapeDecisionByMaterialCode = immutableMap(shapeDecisionByMaterialCode);
    if (maxDepth <= 0) {
      throw new IllegalArgumentException("BOM最大层级必须大于0");
    }
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
    return source == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
