package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReference;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceComparison;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxIssue;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.service.PriceLinkedType2PriceReconciler;
import com.sanhua.marketingcost.service.PriceLinkedType2VatRateResolver;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PriceLinkedType2PriceReconcilerImpl
    implements PriceLinkedType2PriceReconciler {

  public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.0001");
  private static final MathContext TAX_MATH_CONTEXT =
      new MathContext(20, RoundingMode.HALF_UP);

  private final PriceLinkedType2VatRateResolver vatRateResolver;

  public PriceLinkedType2PriceReconcilerImpl(
      PriceLinkedType2VatRateResolver vatRateResolver) {
    this.vatRateResolver = vatRateResolver;
  }

  @Override
  public PriceLinkedType2PriceReconcileResult reconcile(
      PriceLinkedType2MergedRow mergedRow,
      PriceLinkedType2FormulaConversionResult formulaConversion,
      PriceLinkedType2TaxNormalizationResult taxNormalization,
      BigDecimal requestedTolerance) {
    List<PriceLinkedType2TaxIssue> warnings = new ArrayList<>();
    List<PriceLinkedType2TaxIssue> errors = new ArrayList<>();
    BigDecimal tolerance = normalizeTolerance(requestedTolerance, errors);
    if (taxNormalization != null) {
      warnings.addAll(taxNormalization.getWarnings());
    }
    if (mergedRow == null || mergedRow.getBusinessRow() == null) {
      errors.add(issue("MERGED_ROW_MISSING", "类型 2 合并产品行不能为空"));
      return result(null, null, null, tolerance, null, null, null, warnings, errors);
    }
    if (formulaConversion == null || !formulaConversion.isSuccess()) {
      errors.add(issue(
          "FORMULA_CONVERSION_FAILED",
          "类型 2 公式尚未安全转换，不能进行价格对账"));
    }
    if (taxNormalization == null || !taxNormalization.isSuccess()) {
      errors.add(issue(
          "TAX_NORMALIZATION_FAILED",
          "类型 2 税口径规范化失败，不能进行价格对账"));
      if (taxNormalization != null) {
        errors.addAll(taxNormalization.getErrors());
      }
    }
    if (!errors.isEmpty()) {
      return result(
          null,
          null,
          null,
          tolerance,
          taxNormalization == null ? null : taxNormalization.getNormalizedTaxIncluded(),
          null,
          null,
          warnings,
          errors);
    }

    BigDecimal formulaResult = evaluateFormula(formulaConversion, errors);
    PriceLinkedType2ProductRow productRow = mergedRow.getBusinessRow();
    PriceLinkedType2PriceComparison includedComparison = null;
    if (formulaResult != null) {
      includedComparison = compare(
          "TAX_INCLUDED",
          formulaResult,
          productRow.getTaxIncludedPrice(),
          tolerance,
          "EXCEL_TAX_INCLUDED_PRICE_MISSING",
          "TAX_INCLUDED_PRICE_MISMATCH",
          errors);
    }

    boolean requiresVatRate =
        taxNormalization.isTaxAdjustmentRequired()
            || productRow.getTaxExcludedPrice() != null;
    BigDecimal vatRate = requiresVatRate
        ? resolveVatRate(mergedRow, errors)
        : null;
    BigDecimal taxExcludedResult = null;
    if (formulaResult != null && requiresVatRate && vatRate != null) {
      BigDecimal divisor = BigDecimal.ONE.add(vatRate);
      if (divisor.compareTo(BigDecimal.ZERO) <= 0) {
        errors.add(issue(
            "VAT_RATE_INVALID",
            "税率非法：1+vat_rate 必须大于 0，实际 vat_rate=" + vatRate));
      } else {
        taxExcludedResult = formulaResult.divide(divisor, TAX_MATH_CONTEXT);
      }
    }

    PriceLinkedType2PriceComparison excludedComparison = null;
    if (productRow.getTaxExcludedPrice() != null && taxExcludedResult != null) {
      excludedComparison = compare(
          "TAX_EXCLUDED",
          taxExcludedResult,
          productRow.getTaxExcludedPrice(),
          tolerance,
          null,
          "TAX_EXCLUDED_PRICE_MISMATCH",
          errors);
    }
    BigDecimal finalPrice = taxNormalization.isTaxAdjustmentRequired()
        ? taxExcludedResult
        : formulaResult;
    return result(
        formulaResult,
        finalPrice,
        vatRate,
        tolerance,
        taxNormalization.getNormalizedTaxIncluded(),
        includedComparison,
        excludedComparison,
        warnings,
        errors);
  }

  private BigDecimal evaluateFormula(
      PriceLinkedType2FormulaConversionResult formulaConversion,
      List<PriceLinkedType2TaxIssue> errors) {
    Map<String, BigDecimal> factorValues = new LinkedHashMap<>();
    for (PriceLinkedType2FormulaReference reference
        : formulaConversion.getFactorReplacements()) {
      if (reference.factorIdentityId() == null || reference.numericValue() == null) {
        errors.add(issue(
            "FACTOR_PRICE_MISSING",
            "因素替换缺少统一身份或导入价格：" + reference.rawReference()));
        continue;
      }
      String code = "factor_identity_" + reference.factorIdentityId();
      BigDecimal previous = factorValues.putIfAbsent(code, reference.numericValue());
      if (previous != null && previous.compareTo(reference.numericValue()) != 0) {
        errors.add(issue(
            "FACTOR_PRICE_CONFLICT",
            "同一统一因素在公式快照中存在不同价格：" + code));
      }
    }
    if (!errors.isEmpty()) {
      return null;
    }
    try {
      BigDecimal evaluated = ExpressionEvaluator.evaluate(
          formulaConversion.getConvertedFormula(), factorValues);
      if (evaluated == null) {
        errors.add(issue("FORMULA_RESULT_MISSING", "转换后公式求值结果为空"));
      }
      return evaluated;
    } catch (RuntimeException ex) {
      errors.add(issue(
          "FORMULA_EVALUATION_FAILED",
          "转换后公式求值失败：" + ex.getMessage()));
      return null;
    }
  }

  private BigDecimal resolveVatRate(
      PriceLinkedType2MergedRow mergedRow,
      List<PriceLinkedType2TaxIssue> errors) {
    Optional<BigDecimal> resolved;
    try {
      resolved = vatRateResolver.resolve(mergedRow);
    } catch (RuntimeException ex) {
      errors.add(issue(
          "VAT_RATE_RESOLUTION_FAILED",
          "税率解析失败：vat_rate -> " + ex.getMessage()));
      return null;
    }
    if (resolved.isEmpty()) {
      errors.add(issue("VAT_RATE_MISSING", "税率未配置：vat_rate"));
      return null;
    }
    return resolved.get();
  }

  private PriceLinkedType2PriceComparison compare(
      String priceType,
      BigDecimal systemPrice,
      BigDecimal excelPrice,
      BigDecimal tolerance,
      String missingCode,
      String mismatchCode,
      List<PriceLinkedType2TaxIssue> errors) {
    if (systemPrice == null || excelPrice == null) {
      if (missingCode != null) {
        errors.add(issue(missingCode, priceType + " 的 Excel 价格快照为空"));
      }
      return new PriceLinkedType2PriceComparison(
          priceType, systemPrice, excelPrice, null, tolerance, false, missingCode == null);
    }
    BigDecimal difference = systemPrice.subtract(excelPrice).abs();
    boolean passed = difference.compareTo(tolerance) <= 0;
    if (!passed) {
      errors.add(issue(
          mismatchCode,
          priceType + " 对账差异超过允许误差：系统=" + systemPrice
              + "，Excel=" + excelPrice
              + "，差异=" + difference
              + "，门限=" + tolerance));
    }
    return new PriceLinkedType2PriceComparison(
        priceType,
        systemPrice,
        excelPrice,
        difference,
        tolerance,
        true,
        passed);
  }

  private BigDecimal normalizeTolerance(
      BigDecimal requested,
      List<PriceLinkedType2TaxIssue> errors) {
    if (requested == null) {
      return DEFAULT_TOLERANCE;
    }
    if (requested.compareTo(BigDecimal.ZERO) < 0) {
      errors.add(issue("TOLERANCE_INVALID", "允许误差不能小于 0"));
      return DEFAULT_TOLERANCE;
    }
    return requested;
  }

  private PriceLinkedType2PriceReconcileResult result(
      BigDecimal formulaResult,
      BigDecimal finalPrice,
      BigDecimal vatRate,
      BigDecimal tolerance,
      Integer normalizedTaxIncluded,
      PriceLinkedType2PriceComparison includedComparison,
      PriceLinkedType2PriceComparison excludedComparison,
      List<PriceLinkedType2TaxIssue> warnings,
      List<PriceLinkedType2TaxIssue> errors) {
    return new PriceLinkedType2PriceReconcileResult(
        formulaResult,
        finalPrice,
        vatRate,
        tolerance,
        normalizedTaxIncluded,
        includedComparison,
        excludedComparison,
        warnings,
        errors);
  }

  private PriceLinkedType2TaxIssue issue(String code, String message) {
    return new PriceLinkedType2TaxIssue(code, message);
  }
}
