package com.sanhua.marketingcost.service.effectivebom;

/** 创建或复用后的不可变有效 BOM 构建。 */
public record QuoteEffectiveBomPersistenceResult(
    String buildBatchId,
    String variantHash,
    boolean reused,
    int nodeCount) {}
