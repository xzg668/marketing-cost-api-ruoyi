package com.sanhua.marketingcost.service.settlement;

/** 统一 BOM 结算行生成统计，供后续日志、验收对账和回归测试使用。 */
public record BomSettlementRowBuildStats(
    int inputNodeCount,
    int costingRowCount,
    int subRefCount,
    int sourceRefCount,
    int warningCount,
    int stoppedPathCount,
    int consumedLeafPathCount,
    int rollupBucketCount,
    int extraRowBucketCount) {}
