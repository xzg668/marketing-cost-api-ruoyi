package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import java.util.List;

/** 不可变最终有效 BOM 仓储；已确认构建只读，仅可清理未被确认或月度指针引用的草稿构建。 */
public interface QuoteEffectiveBomRepository {

  List<String> findBuildBatchIdsByVariantHash(String variantHash);

  List<QuoteEffectiveBomNode> findNodesByBuildBatchId(String buildBatchId);

  boolean existsBuildBatchId(String buildBatchId);

  void insertAll(List<QuoteEffectiveBomNode> nodes);

  default int deleteUnreferencedByOriginMonthlySnapshotId(Long monthlySnapshotId) {
    throw new UnsupportedOperationException();
  }
}
