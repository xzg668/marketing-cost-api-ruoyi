package com.sanhua.marketingcost.service.effectivebom;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 影响最终有效 BOM 结构的通用规则动作。 */
public record EffectiveBomPolicyAction(
    Set<String> excludedDirectChildMaterialCodes) {

  public EffectiveBomPolicyAction {
    excludedDirectChildMaterialCodes =
        excludedDirectChildMaterialCodes == null
            ? Set.of()
            : Collections.unmodifiableSet(
                new LinkedHashSet<>(excludedDirectChildMaterialCodes));
  }

  public static EffectiveBomPolicyAction none() {
    return new EffectiveBomPolicyAction(Set.of());
  }
}
