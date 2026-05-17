package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedFormulaFactorRefParserImpl implements PriceLinkedFormulaFactorRefParser {

  private static final Pattern SHEET_CELL_REF_PATTERN = Pattern.compile(
      "(?:'((?:[^']|'')+)'|([^'!+\\-*/(),=<>\\s]+))!\\$?([A-Za-z]{1,3})\\$?(\\d+)");
  private static final Pattern EXTERNAL_SHEET_PATTERN = Pattern.compile("^\\[([^]]+)](.+)$");

  @Override
  public List<FormulaFactorRef> parse(String priceCellFormula) {
    List<FormulaFactorRef> refs = new ArrayList<>();
    if (!StringUtils.hasText(priceCellFormula)) {
      return refs;
    }
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = SHEET_CELL_REF_PATTERN.matcher(priceCellFormula);
    while (matcher.find()) {
      String sheetToken = unescapeQuotedSheetName(
          StringUtils.hasText(matcher.group(1)) ? matcher.group(1) : matcher.group(2));
      SheetIdentity identity = parseSheetIdentity(sheetToken);
      if (!StringUtils.hasText(identity.sheetName())
          || !identity.sheetName().contains("影响因素")) {
        continue;
      }
      Integer rowNumber = parseInteger(matcher.group(4));
      if (rowNumber == null) {
        continue;
      }
      String columnName = matcher.group(3).toUpperCase(Locale.ROOT);
      String dedupeKey = trimToEmpty(identity.workbookName()) + "|"
          + identity.sheetName() + "|" + rowNumber;
      if (!seen.add(dedupeKey)) {
        continue;
      }

      FormulaFactorRef ref = new FormulaFactorRef();
      ref.setWorkbookName(identity.workbookName());
      ref.setSheetName(identity.sheetName());
      ref.setColumnName(columnName);
      ref.setRowNumber(rowNumber);
      ref.setRawRef(matcher.group());
      ref.setOrderIndex(refs.size() + 1);
      refs.add(ref);
    }
    return refs;
  }

  private SheetIdentity parseSheetIdentity(String sheetToken) {
    if (!StringUtils.hasText(sheetToken)) {
      return new SheetIdentity(null, null);
    }
    String trimmed = sheetToken.trim();
    Matcher matcher = EXTERNAL_SHEET_PATTERN.matcher(trimmed);
    if (matcher.matches()) {
      return new SheetIdentity(matcher.group(1), matcher.group(2));
    }
    return new SheetIdentity(null, trimmed);
  }

  private String unescapeQuotedSheetName(String sheetName) {
    return sheetName == null ? null : sheetName.replace("''", "'");
  }

  private Integer parseInteger(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String trimToEmpty(String text) {
    return StringUtils.hasText(text) ? text.trim() : "";
  }

  private record SheetIdentity(String workbookName, String sheetName) {
  }
}
