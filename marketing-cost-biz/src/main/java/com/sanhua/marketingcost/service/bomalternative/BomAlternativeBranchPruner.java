package com.sanhua.marketingcost.service.bomalternative;

/** 根据报价选择把完整 BOM 收敛成唯一标准/替代分支的纯内存裁剪器。 */
public interface BomAlternativeBranchPruner {

  BomAlternativePruneResult prune(BomAlternativePruneRequest request);
}
