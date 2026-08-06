package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 完整有效 BOM、替代组和当前报价选择组成的不可变裁剪请求。 */
public record BomAlternativePruneRequest(
    List<BomRawHierarchy> nodes,
    List<BomAlternativeGroup> groups,
    Map<String, String> selectedMaterialCodeByGroupKey) {

  public BomAlternativePruneRequest {
    nodes =
        nodes == null
            ? List.of()
            : nodes.stream().filter(Objects::nonNull).toList();
    groups =
        groups == null
            ? List.of()
            : groups.stream().filter(Objects::nonNull).toList();
    selectedMaterialCodeByGroupKey =
        selectedMaterialCodeByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(selectedMaterialCodeByGroupKey));
  }
}
