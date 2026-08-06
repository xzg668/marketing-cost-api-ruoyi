package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaError;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReference;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaReferenceClassification;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.enums.PriceLinkedType2FormulaReferenceType;
import com.sanhua.marketingcost.service.PriceLinkedType2FormulaReferenceClassifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedType2FormulaReferenceClassifierImpl
    implements PriceLinkedType2FormulaReferenceClassifier {

  @Override
  public PriceLinkedType2FormulaReferenceClassification classify(
      PriceLinkedType2ProductRow productRow,
      List<PriceLinkedType2FormulaFactorBinding> factorBindings) {
    List<PriceLinkedType2FormulaReference> references = new ArrayList<>();
    List<PriceLinkedType2FormulaError> errors = new ArrayList<>();
    if (productRow == null) {
      errors.add(error("FORMULA_ROW_MISSING", "类型 2 产品公式行不能为空", null, null));
      return new PriceLinkedType2FormulaReferenceClassification(references, errors);
    }

    Map<String, PriceLinkedType2CellSnapshot> snapshots =
        snapshots(productRow.getReferencedCells(), errors);
    Map<String, PriceLinkedType2FormulaFactorBinding> bindings =
        bindings(factorBindings, errors);
    List<PriceLinkedType2FormulaReferenceSyntax.ReferenceToken> tokens;
    try {
      tokens = PriceLinkedType2FormulaReferenceSyntax.parse(
          productRow.getTaxIncludedFormula(), productRow.getSourceSheetName());
    } catch (RuntimeException ex) {
      errors.add(error(
          "CELL_REFERENCE_INVALID",
          "无法识别公式单元格引用：" + ex.getMessage(),
          productRow.getSourceSheetName(),
          productRow.getFormulaCellRef()));
      return new PriceLinkedType2FormulaReferenceClassification(references, errors);
    }

    Set<String> seen = new LinkedHashSet<>();
    String formulaCellKey = PriceLinkedType2FormulaReferenceSyntax.key(
        productRow.getSourceSheetName(), productRow.getFormulaCellRef());
    for (PriceLinkedType2FormulaReferenceSyntax.ReferenceToken token : tokens) {
      String key = PriceLinkedType2FormulaReferenceSyntax.key(
          token.sheetName(), token.cellRef());
      if (!seen.add(key)) {
        continue;
      }
      PriceLinkedType2CellSnapshot snapshot = snapshots.get(key);
      PriceLinkedType2FormulaFactorBinding binding = bindings.get(key);
      if (key.equals(formulaCellKey)) {
        errors.add(error(
            "CIRCULAR_REFERENCE",
            "现含税价公式直接引用自身 " + token.rawReference(),
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      if (snapshot == null) {
        errors.add(error(
            "REFERENCE_SNAPSHOT_MISSING",
            "公式引用缺少原始单元格快照：" + token.rawReference(),
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      if (binding != null) {
        if (snapshot.getNumericValue() == null) {
          errors.add(error(
              "REFERENCE_NOT_NUMERIC",
              "影响因素价格不是数字：" + token.sheetName() + "!" + token.cellRef()
                  + "（" + displayHeader(snapshot) + "）",
              token.sheetName(),
              token.cellRef()));
          continue;
        }
        if (binding.factorIdentityId() == null) {
          errors.add(error(
              "FACTOR_IDENTITY_MISSING",
              "因素单元格尚未解析出统一主身份：" + token.rawReference(),
              token.sheetName(),
              token.cellRef()));
          continue;
        }
        references.add(new PriceLinkedType2FormulaReference(
            token.rawReference(),
            token.sheetName(),
            token.cellRef(),
            snapshot.getHeader(),
            snapshot.getUnit(),
            snapshot.getNumericValue(),
            snapshot.getFormula(),
            PriceLinkedType2FormulaReferenceType.FACTOR_DYNAMIC,
            binding.factorIdentityId(),
            binding.shortName(),
            "[factor_identity_" + binding.factorIdentityId() + "]"));
        continue;
      }

      if (!sameSheet(token.sheetName(), productRow.getSourceSheetName())) {
        errors.add(error(
            "UNKNOWN_CROSS_SHEET_REFERENCE",
            "公式引用了无法识别业务含义的跨 Sheet 单元格："
                + token.sheetName() + "!" + token.cellRef(),
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      if (productRow.getSourceRowNumber() == null
          || token.rowNumber() != productRow.getSourceRowNumber()) {
        errors.add(error(
            "OTHER_PRODUCT_ROW_REFERENCE",
            "公式引用了其他产品行：" + token.rawReference()
                + "，当前产品行为 " + productRow.getSourceRowNumber(),
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      if (referencesFormulaCell(snapshot, formulaCellKey)) {
        errors.add(error(
            "CIRCULAR_REFERENCE",
            "输入单元格 " + token.rawReference() + " 的公式回指现含税价单元格",
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      BigDecimal effectiveValue = snapshot.getNumericValue();
      if (effectiveValue == null && snapshot.isBlankCell()) {
        effectiveValue = BigDecimal.ZERO;
      }
      if (effectiveValue == null) {
        errors.add(error(
            "REFERENCE_NOT_NUMERIC",
            "公式引用值不是数字：" + token.sheetName() + "!" + token.cellRef()
                + "（" + displayHeader(snapshot) + "）",
            token.sheetName(),
            token.cellRef()));
        continue;
      }
      references.add(new PriceLinkedType2FormulaReference(
          token.rawReference(),
          token.sheetName(),
          token.cellRef(),
          snapshot.getHeader(),
          snapshot.getUnit(),
          effectiveValue,
          snapshot.getFormula(),
          PriceLinkedType2FormulaReferenceType.ROW_NUMERIC,
          null,
          null,
          numberLiteral(effectiveValue)));
    }
    return new PriceLinkedType2FormulaReferenceClassification(references, errors);
  }

  private Map<String, PriceLinkedType2CellSnapshot> snapshots(
      List<PriceLinkedType2CellSnapshot> input,
      List<PriceLinkedType2FormulaError> errors) {
    Map<String, PriceLinkedType2CellSnapshot> result = new LinkedHashMap<>();
    if (input == null) {
      return result;
    }
    for (PriceLinkedType2CellSnapshot snapshot : input) {
      if (snapshot == null) {
        continue;
      }
      String key = PriceLinkedType2FormulaReferenceSyntax.key(
          snapshot.getSheetName(), snapshot.getCellRef());
      PriceLinkedType2CellSnapshot previous = result.putIfAbsent(key, snapshot);
      if (previous != null && !sameSnapshot(previous, snapshot)) {
        errors.add(error(
            "REFERENCE_SNAPSHOT_CONFLICT",
            "同一单元格存在互相冲突的输入快照：" + key,
            snapshot.getSheetName(),
            snapshot.getCellRef()));
      }
    }
    return result;
  }

  private Map<String, PriceLinkedType2FormulaFactorBinding> bindings(
      List<PriceLinkedType2FormulaFactorBinding> input,
      List<PriceLinkedType2FormulaError> errors) {
    Map<String, PriceLinkedType2FormulaFactorBinding> result = new LinkedHashMap<>();
    if (input == null) {
      return result;
    }
    for (PriceLinkedType2FormulaFactorBinding binding : input) {
      if (binding == null) {
        continue;
      }
      String key = PriceLinkedType2FormulaReferenceSyntax.key(
          binding.sheetName(), binding.cellRef());
      PriceLinkedType2FormulaFactorBinding previous = result.putIfAbsent(key, binding);
      if (previous != null
          && !Objects.equals(previous.factorIdentityId(), binding.factorIdentityId())) {
        errors.add(error(
            "FACTOR_BINDING_CONFLICT",
            "同一因素单元格对应多个统一身份：" + key,
            binding.sheetName(),
            binding.cellRef()));
      }
    }
    return result;
  }

  private boolean referencesFormulaCell(
      PriceLinkedType2CellSnapshot snapshot, String formulaCellKey) {
    if (!StringUtils.hasText(snapshot.getFormula())) {
      return false;
    }
    try {
      return PriceLinkedType2FormulaReferenceSyntax.parse(
              snapshot.getFormula(), snapshot.getSheetName()).stream()
          .map(token -> PriceLinkedType2FormulaReferenceSyntax.key(
              token.sheetName(), token.cellRef()))
          .anyMatch(formulaCellKey::equals);
    } catch (RuntimeException ex) {
      return true;
    }
  }

  private boolean sameSnapshot(
      PriceLinkedType2CellSnapshot left, PriceLinkedType2CellSnapshot right) {
    return sameNumber(left.getNumericValue(), right.getNumericValue())
        && Objects.equals(left.getDisplayValue(), right.getDisplayValue())
        && Objects.equals(left.getFormula(), right.getFormula())
        && Objects.equals(left.getSourceCellType(), right.getSourceCellType())
        && left.isBlankCell() == right.isBlankCell();
  }

  private boolean sameNumber(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.compareTo(right) == 0;
  }

  private String numberLiteral(BigDecimal value) {
    BigDecimal normalized = value.stripTrailingZeros();
    String literal = normalized.toPlainString();
    return normalized.signum() < 0 ? "(" + literal + ")" : literal;
  }

  private boolean sameSheet(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private String displayHeader(PriceLinkedType2CellSnapshot snapshot) {
    return StringUtils.hasText(snapshot.getHeader())
        ? snapshot.getHeader()
        : snapshot.getCellRef();
  }

  private PriceLinkedType2FormulaError error(
      String code, String message, String sheetName, String cellRef) {
    return new PriceLinkedType2FormulaError(code, message, sheetName, cellRef);
  }
}
