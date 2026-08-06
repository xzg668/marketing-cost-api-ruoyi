package com.sanhua.marketingcost.service.effectivebom;

/** 为最终有效 BOM 业务内容生成稳定指纹。 */
@FunctionalInterface
public interface EffectiveBomVariantHasher {

  String hash(EffectiveBomVariantInput input);
}
