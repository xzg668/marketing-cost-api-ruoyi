package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;

/** 规范化类型 2 标准行的“是否含税”和公式末尾除税兼容规则。 */
public interface PriceLinkedType2TaxNormalizer {

  PriceLinkedType2TaxNormalizationResult normalize(
      String taxIncludedText,
      PriceLinkedType2FormulaConversionResult formulaConversion);
}
