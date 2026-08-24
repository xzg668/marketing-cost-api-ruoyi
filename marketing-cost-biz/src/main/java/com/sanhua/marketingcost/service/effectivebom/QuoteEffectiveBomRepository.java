package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import java.util.List;

/** 不可变有效 BOM 构建仓储；相同内容可复用，已写入构建不覆盖。 */
public interface QuoteEffectiveBomRepository {

  List<String> findBuildBatchIdsByVariantHash(String variantHash);

  List<QuoteEffectiveBomNode> findNodesByBuildBatchId(String buildBatchId);

  boolean existsBuildBatchId(String buildBatchId);

  void insertAll(List<QuoteEffectiveBomNode> nodes);

}
