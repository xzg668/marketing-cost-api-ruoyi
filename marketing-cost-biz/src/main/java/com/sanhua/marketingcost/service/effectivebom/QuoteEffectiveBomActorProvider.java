package com.sanhua.marketingcost.service.effectivebom;

/** 最终BOM冻结动作的当前操作人。 */
@FunctionalInterface
public interface QuoteEffectiveBomActorProvider {

  Long currentUserId();
}
