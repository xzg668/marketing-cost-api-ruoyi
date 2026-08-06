package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import java.util.List;

/** 将类型 2 的“现含税价”Excel 公式转换成系统四则运算公式。 */
public interface PriceLinkedType2FormulaConverter {

  PriceLinkedType2FormulaConversionResult convert(
      PriceLinkedType2ProductRow productRow,
      List<PriceLinkedType2FormulaFactorBinding> factorBindings);
}
