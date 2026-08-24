package com.sanhua.marketingcost.service.effectivebom;

/** 当前有效 BOM 构建的持久化入口。 */
public interface QuoteEffectiveBomPersistenceService {

  QuoteEffectiveBomPersistenceResult persistCurrentVariant(
      QuoteEffectiveBomPersistenceRequest request);
}
