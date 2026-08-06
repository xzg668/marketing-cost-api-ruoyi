package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import java.math.BigDecimal;

/** 对账类型 2 转换公式的含税结果、不含税结果及 Excel 价格快照。 */
public interface PriceLinkedType2PriceReconciler {

  PriceLinkedType2PriceReconcileResult reconcile(
      PriceLinkedType2MergedRow mergedRow,
      PriceLinkedType2FormulaConversionResult formulaConversion,
      PriceLinkedType2TaxNormalizationResult taxNormalization,
      BigDecimal tolerance);
}
