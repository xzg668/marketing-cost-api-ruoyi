package com.sanhua.marketingcost.service;

/** 类型 2 两个 Sheet 共用的文本标准化契约。 */
public interface PriceLinkedType2TextNormalizer {

  String normalize(String value);

  String normalizeHeader(String value);
}
