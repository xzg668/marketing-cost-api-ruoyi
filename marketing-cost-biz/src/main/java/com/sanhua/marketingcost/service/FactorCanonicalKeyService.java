package com.sanhua.marketingcost.service;

/** 由取价来源和简称生成稳定的统一因素键。 */
public interface FactorCanonicalKeyService {

  /**
   * 生成统一因素键，例如 {@code 平均价 + 1#Cu -> AVG|1#CU}。
   *
   * @return 任一必要字段为空时返回空字符串
   */
  String build(String priceSource, String shortName);

  /** 标准化数据库中已经保存的统一因素键。 */
  String normalizeExistingKey(String canonicalFactorKey);
}
