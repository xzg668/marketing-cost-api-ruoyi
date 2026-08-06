package com.sanhua.marketingcost.service.effectivebom;

/** 新最终有效 BOM 构建编号生成器。 */
@FunctionalInterface
public interface EffectiveBomBuildIdGenerator {

  String nextId();
}
