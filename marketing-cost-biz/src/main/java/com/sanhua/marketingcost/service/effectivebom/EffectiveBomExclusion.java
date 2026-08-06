package com.sanhua.marketingcost.service.effectivebom;

/** 一次整支排除的可解释摘要。 */
public record EffectiveBomExclusion(
    String reasonCode,
    String triggerMaterialCode,
    String triggerSourcePath,
    String excludedRootMaterialCode,
    String excludedRootSourcePath,
    int excludedNodeCount,
    String message) {}
