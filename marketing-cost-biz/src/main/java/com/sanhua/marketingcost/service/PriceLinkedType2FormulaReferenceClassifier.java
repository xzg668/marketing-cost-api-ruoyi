package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReferenceClassification;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import java.util.List;

/** 将 Excel 公式引用严格分类为动态因素或本行数值。 */
public interface PriceLinkedType2FormulaReferenceClassifier {

  PriceLinkedType2FormulaReferenceClassification classify(
      PriceLinkedType2ProductRow productRow,
      List<PriceLinkedType2FormulaFactorBinding> factorBindings);
}
