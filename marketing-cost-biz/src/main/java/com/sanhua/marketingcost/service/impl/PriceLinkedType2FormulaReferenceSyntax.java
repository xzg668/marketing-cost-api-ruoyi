package com.sanhua.marketingcost.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;
import org.springframework.util.StringUtils;

/** 类型 2 公式引用的轻量解析工具；只识别单个 A1 引用，不猜测区域和名称引用。 */
final class PriceLinkedType2FormulaReferenceSyntax {

  private static final Pattern CELL_REFERENCE = Pattern.compile(
      "(?<![A-Za-z0-9_])"
          + "(?:(?:'((?:[^']|'')+)'|([\\p{L}\\p{N}_ .]+))!)?"
          + "(\\$?[A-Za-z]{1,3}\\$?\\d+)");

  private PriceLinkedType2FormulaReferenceSyntax() {
  }

  static List<ReferenceToken> parse(String formula, String defaultSheetName) {
    List<ReferenceToken> tokens = new ArrayList<>();
    if (!StringUtils.hasText(formula)) {
      return tokens;
    }
    Matcher matcher = CELL_REFERENCE.matcher(formula);
    while (matcher.find()) {
      String explicitSheet = firstNonBlank(matcher.group(1), matcher.group(2));
      String sheetName = explicitSheet == null
          ? defaultSheetName
          : explicitSheet.replace("''", "'");
      String rawCellRef = matcher.group(3);
      CellReference cellReference =
          new CellReference(rawCellRef.replace("$", ""));
      String canonicalCellRef =
          new CellReference(cellReference.getRow(), cellReference.getCol()).formatAsString();
      tokens.add(new ReferenceToken(
          matcher.start(),
          matcher.end(),
          matcher.group(),
          sheetName,
          canonicalCellRef,
          cellReference.getRow() + 1));
    }
    return List.copyOf(tokens);
  }

  static String key(String sheetName, String cellRef) {
    return normalize(sheetName) + "!" + normalize(cellRef);
  }

  private static String firstNonBlank(String first, String second) {
    return StringUtils.hasText(first) ? first
        : StringUtils.hasText(second) ? second : null;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace("$", "").trim()
        .toUpperCase(java.util.Locale.ROOT);
  }

  record ReferenceToken(
      int start,
      int end,
      String rawReference,
      String sheetName,
      String cellRef,
      int rowNumber) {
  }
}
