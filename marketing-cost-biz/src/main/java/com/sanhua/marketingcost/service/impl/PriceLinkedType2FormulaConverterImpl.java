package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.formula.workbook.ExcelCellReferences;

import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaError;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReference;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReferenceClassification;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.service.PriceLinkedType2FormulaConverter;
import com.sanhua.marketingcost.service.PriceLinkedType2FormulaReferenceClassifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedType2FormulaConverterImpl implements PriceLinkedType2FormulaConverter {

  private static final Pattern FUNCTION_CALL =
      Pattern.compile("(?i)(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(");
  private static final Pattern CELL_RANGE =
      Pattern.compile("(?i)\\$?[A-Z]{1,3}\\$?\\d+\\s*:\\s*\\$?[A-Z]{1,3}\\$?\\d+");

  private final PriceLinkedType2FormulaReferenceClassifier referenceClassifier;

  public PriceLinkedType2FormulaConverterImpl(
      PriceLinkedType2FormulaReferenceClassifier referenceClassifier) {
    this.referenceClassifier = referenceClassifier;
  }

  @Override
  public PriceLinkedType2FormulaConversionResult convert(
      PriceLinkedType2ProductRow productRow,
      List<PriceLinkedType2FormulaFactorBinding> factorBindings) {
    if (productRow == null) {
      return result(
          null,
          null,
          null,
          null,
          false,
          List.of(),
          List.of(),
          List.of(error("FORMULA_ROW_MISSING", "类型 2 产品公式行不能为空", null, null)));
    }

    String sourceFormula = productRow.getTaxIncludedFormula();
    List<PriceLinkedType2FormulaError> errors = new ArrayList<>();
    if (!StringUtils.hasText(sourceFormula)) {
      errors.add(
          error(
              "EMPTY_FORMULA",
              "现含税价缺少 Excel 公式",
              productRow.getSourceSheetName(),
              productRow.getFormulaCellRef()));
      return blocked(productRow, sourceFormula, false, List.of(), errors);
    }

    String formula = normalizeExcelOperators(stripLeadingEquals(sourceFormula));
    validateUnsupportedSyntax(productRow, formula, errors);
    VatStripResult vatResult = stripFinalVatDivisor(formula);
    formula = vatResult.formula();

    PriceLinkedType2FormulaReferenceClassification classification =
        referenceClassifier.classify(productRow, factorBindings);
    errors.addAll(classification.getErrors());
    if (!errors.isEmpty()) {
      return blocked(
          productRow, sourceFormula, vatResult.stripped(), classification.getReferences(), errors);
    }

    String converted;
    try {
      converted =
          replaceReferences(
              formula, productRow.getSourceSheetName(), classification.getReferences());
    } catch (RuntimeException ex) {
      errors.add(
          error(
              "REFERENCE_REPLACEMENT_FAILED",
              "公式引用替换失败：" + ex.getMessage(),
              productRow.getSourceSheetName(),
              productRow.getFormulaCellRef()));
      return blocked(
          productRow, sourceFormula, vatResult.stripped(), classification.getReferences(), errors);
    }
    converted = compact(converted);
    validateConvertedFormula(productRow, converted, errors);
    return result(
        productRow,
        sourceFormula,
        errors.isEmpty() ? converted : null,
        vatResult.stripped(),
        classification.getReferences(),
        errors);
  }

  private void validateUnsupportedSyntax(
      PriceLinkedType2ProductRow row, String formula, List<PriceLinkedType2FormulaError> errors) {
    if (formula.indexOf('[') >= 0 || formula.indexOf(']') >= 0) {
      errors.add(
          error(
              "EXTERNAL_WORKBOOK_REFERENCE",
              "公式包含外部工作簿或结构化引用，类型 2 不允许自动转换",
              row.getSourceSheetName(),
              row.getFormulaCellRef()));
    }
    if (CELL_RANGE.matcher(formula).find()) {
      errors.add(
          error(
              "CELL_RANGE_UNSUPPORTED",
              "公式包含单元格区域引用，当前只支持可追溯的单个单元格",
              row.getSourceSheetName(),
              row.getFormulaCellRef()));
    }
    if (formula.indexOf('"') >= 0 || formula.indexOf(';') >= 0) {
      errors.add(
          error(
              "FORMULA_TOKEN_UNSUPPORTED",
              "公式包含字符串或不支持的参数分隔符",
              row.getSourceSheetName(),
              row.getFormulaCellRef()));
    }
    Matcher matcher = FUNCTION_CALL.matcher(formula);
    while (matcher.find()) {
      String function = matcher.group(1).toUpperCase(Locale.ROOT);
      if (!"ROUND".equals(function) && !"INT".equals(function)) {
        errors.add(
            error(
                "FUNCTION_UNSUPPORTED",
                "不支持的 Excel 函数：" + function,
                row.getSourceSheetName(),
                row.getFormulaCellRef()));
      }
    }
  }

  private VatStripResult stripFinalVatDivisor(String formula) {
    int slash = findFinalTopLevelSlash(formula);
    if (slash < 0) {
      return new VatStripResult(formula, false);
    }
    String divisor = formula.substring(slash + 1).trim();
    if (!"1.13".equals(divisor)) {
      return new VatStripResult(formula, false);
    }
    String stripped = formula.substring(0, slash).trim();
    return StringUtils.hasText(stripped)
        ? new VatStripResult(stripped, true)
        : new VatStripResult(formula, false);
  }

  private String replaceReferences(
      String formula, String defaultSheetName, List<PriceLinkedType2FormulaReference> references) {
    Map<String, PriceLinkedType2FormulaReference> byCell = new LinkedHashMap<>();
    for (PriceLinkedType2FormulaReference reference : references) {
      byCell.put(
          ExcelCellReferences.key(reference.sheetName(), reference.cellRef()),
          reference);
    }
    List<ExcelCellReferences.ReferenceToken> tokens =
        ExcelCellReferences.parse(formula, defaultSheetName);
    StringBuilder out = new StringBuilder(formula.length() + 32);
    int cursor = 0;
    for (ExcelCellReferences.ReferenceToken token : tokens) {
      out.append(formula, cursor, token.start());
      PriceLinkedType2FormulaReference reference =
          byCell.get(
              ExcelCellReferences.key(token.sheetName(), token.cellRef()));
      if (reference == null || !StringUtils.hasText(reference.replacement())) {
        throw new IllegalStateException("缺少引用替换方案：" + token.sheetName() + "!" + token.cellRef());
      }
      out.append(reference.replacement());
      cursor = token.end();
    }
    out.append(formula.substring(cursor));
    return out.toString();
  }

  private void validateConvertedFormula(
      PriceLinkedType2ProductRow row, String formula, List<PriceLinkedType2FormulaError> errors) {
    if (!ExcelCellReferences.parse(formula, row.getSourceSheetName())
        .isEmpty()) {
      errors.add(
          error(
              "CELL_REFERENCE_REMAINS",
              "转换后公式仍残留 Excel 单元格引用",
              row.getSourceSheetName(),
              row.getFormulaCellRef()));
      return;
    }
    try {
      validateArithmeticFormula(formula);
    } catch (IllegalArgumentException ex) {
      errors.add(
          error(
              "CONVERTED_FORMULA_INVALID",
              ex.getMessage(),
              row.getSourceSheetName(),
              row.getFormulaCellRef()));
    }
  }

  private void validateArithmeticFormula(String formula) {
    com.sanhua.marketingcost.formula.registry.ExpressionEvaluator.validate(formula);
    for (String variable :
        com.sanhua.marketingcost.formula.registry.ExpressionEvaluator.extractVariables(formula)) {
      if (!variable.matches("factor_identity_[1-9]\\d*")) {
        throw new IllegalArgumentException("转换后公式含未知变量：[" + variable + "]");
      }
    }
  }

  private int findFinalTopLevelSlash(String formula) {
    int depth = 0;
    int slash = -1;
    for (int index = 0; index < formula.length(); index++) {
      char current = formula.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')') {
        depth = Math.max(0, depth - 1);
      } else if (current == '/' && depth == 0) {
        slash = index;
      }
    }
    return slash;
  }

  private String stripLeadingEquals(String formula) {
    String trimmed = formula.trim();
    return trimmed.startsWith("=") ? trimmed.substring(1).trim() : trimmed;
  }

  private String normalizeExcelOperators(String formula) {
    return formula
        .replace('（', '(')
        .replace('）', ')')
        .replace('＋', '+')
        .replace('－', '-')
        .replace('＊', '*')
        .replace('／', '/')
        .replace('，', ',');
  }

  private String compact(String formula) {
    return formula.replaceAll("\\s+", "");
  }

  private PriceLinkedType2FormulaConversionResult blocked(
      PriceLinkedType2ProductRow row,
      String sourceFormula,
      boolean vatStripped,
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    return result(row, sourceFormula, null, vatStripped, references, errors);
  }

  private PriceLinkedType2FormulaConversionResult result(
      PriceLinkedType2ProductRow row,
      String sourceFormula,
      String convertedFormula,
      boolean vatStripped,
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    return result(
        row == null ? null : row.getSourceSheetName(),
        row == null ? null : row.getSourceRowNumber(),
        row == null ? null : row.getFormulaCellRef(),
        sourceFormula,
        convertedFormula,
        vatStripped,
        row == null ? List.of() : row.getReferencedCells(),
        references,
        errors);
  }

  private PriceLinkedType2FormulaConversionResult result(
      String sheetName,
      Integer rowNumber,
      String formulaCellRef,
      String sourceFormula,
      boolean vatStripped,
      List<com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot> snapshots,
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    return result(
        sheetName,
        rowNumber,
        formulaCellRef,
        sourceFormula,
        null,
        vatStripped,
        snapshots,
        references,
        errors);
  }

  private PriceLinkedType2FormulaConversionResult result(
      String sheetName,
      Integer rowNumber,
      String formulaCellRef,
      String sourceFormula,
      String convertedFormula,
      boolean vatStripped,
      List<com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot> snapshots,
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    return new PriceLinkedType2FormulaConversionResult(
        sheetName,
        rowNumber,
        formulaCellRef,
        sourceFormula,
        convertedFormula,
        vatStripped,
        snapshots,
        references,
        errors);
  }

  private PriceLinkedType2FormulaError error(
      String code, String message, String sheetName, String cellRef) {
    return new PriceLinkedType2FormulaError(code, message, sheetName, cellRef);
  }

  private record VatStripResult(String formula, boolean stripped) {}
}
