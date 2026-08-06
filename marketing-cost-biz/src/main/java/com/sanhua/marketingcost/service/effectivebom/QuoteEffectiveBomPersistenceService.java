package com.sanhua.marketingcost.service.effectivebom;

/** 确认阶段最终有效 BOM 持久化入口。 */
public interface QuoteEffectiveBomPersistenceService {

  QuoteEffectiveBomPersistenceResult persistConfirmed(
      QuoteEffectiveBomPersistenceRequest request);
}
