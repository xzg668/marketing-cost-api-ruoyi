package com.sanhua.marketingcost.service.effectivebom;

/** 报价最终有效 BOM 的纯内存构建入口。 */
public interface QuoteEffectiveBomBuilder {

  EffectiveBomBuildResult build(EffectiveBomBuildRequest request);
}
