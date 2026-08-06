package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxIssue;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.service.PriceLinkedType2TaxNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedType2TaxNormalizerImpl
    implements PriceLinkedType2TaxNormalizer {

  @Override
  public PriceLinkedType2TaxNormalizationResult normalize(
      String taxIncludedText,
      PriceLinkedType2FormulaConversionResult formulaConversion) {
    List<PriceLinkedType2TaxIssue> warnings = new ArrayList<>();
    List<PriceLinkedType2TaxIssue> errors = new ArrayList<>();
    Integer parsed = parseTaxIncluded(taxIncludedText, warnings, errors);
    boolean divisorStripped = formulaConversion != null
        && formulaConversion.isFinalVatDivisorStripped();
    if (formulaConversion == null) {
      errors.add(issue(
          "FORMULA_CONVERSION_MISSING",
          "缺少类型 2 公式转换结果，无法确认末尾除税兼容状态"));
    }

    Integer normalized = parsed;
    if (divisorStripped && parsed != null) {
      normalized = 0;
      if (parsed == 1) {
        warnings.add(issue(
            "TAX_INCLUDED_CORRECTED_TO_EXCLUDED",
            "原公式末尾已带除税因子，是否含税已由含税纠正为不含税；"
                + "导入时移除末尾除数，计算阶段只除一次"));
      }
    }
    return new PriceLinkedType2TaxNormalizationResult(
        taxIncludedText,
        parsed,
        normalized,
        divisorStripped,
        normalized != null && normalized == 0,
        warnings,
        errors);
  }

  private Integer parseTaxIncluded(
      String raw,
      List<PriceLinkedType2TaxIssue> warnings,
      List<PriceLinkedType2TaxIssue> errors) {
    if (!StringUtils.hasText(raw)) {
      warnings.add(issue(
          "TAX_INCLUDED_DEFAULTED",
          "是否含税为空，沿用现有系统规则按含税处理"));
      return 1;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    if (List.of("0", "false", "否").contains(normalized)) {
      return 0;
    }
    if (List.of("1", "true", "是").contains(normalized)) {
      return 1;
    }
    errors.add(issue(
        "TAX_INCLUDED_INVALID",
        "无法识别是否含税值：" + raw + "；仅支持 FALSE/0/否 或 TRUE/1/是"));
    return null;
  }

  private PriceLinkedType2TaxIssue issue(String code, String message) {
    return new PriceLinkedType2TaxIssue(code, message);
  }
}
