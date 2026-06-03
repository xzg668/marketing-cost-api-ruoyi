package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class QuoteOaPdfHeaderParser {
  private static final float VALUE_LINE_Y_GAP = 24.0f;
  private static final Pattern BUSINESS_DIVISION_VALUE_PATTERN =
      Pattern.compile("[\\u4E00-\\u9FFF\\w（）()·\\-]{2,}事业部");
  private static final Set<String> COORDINATE_NUMBER_FIELDS =
      Set.of(
          "exchangeRate",
          "copperPrice",
          "zincPrice",
          "aluminumPrice",
          "steelPrice",
          "silverPrice",
          "goldPrice",
          "sus304Price",
          "sus316lPrice",
          "baseShipping");

  public void parse(QuotePdfParseContext context, QuoteIngestRequest request) {
    if (context == null || context.getTemplateDefinition() == null || request == null) {
      return;
    }
    List<String> lines = lines(context.getDocument());
    if (lines.isEmpty()) {
      return;
    }
    List<String> allLabels = allLabels(context.getTemplateDefinition().getHeaderFields());
    for (QuoteOaPdfFieldDefinition field : context.getTemplateDefinition().getHeaderFields()) {
      FieldValue value = findFieldValue(lines, field, allLabels);
      if (value == null || !StringUtils.hasText(value.value())) {
        continue;
      }
      if (!QuoteIngestFieldBinder.applyRequestField(request, field.getFieldCode(), value.value())
          && !QuoteIngestFieldBinder.applyHeaderField(request.getHeader(), field.getFieldCode(), value.value())) {
        request.getExtraFields().add(toExtraField(field, value.value(), value.sourcePath()));
      }
    }
    applyTextHeaderFallbacks(lines, request.getHeader());
    applyCoordinateNumberOverrides(context, request.getHeader());
  }

  private FieldValue findFieldValue(
      List<String> lines, QuoteOaPdfFieldDefinition field, List<String> allLabels) {
    List<String> aliases = fieldLabels(field);
    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      String line = lines.get(lineIndex);
      if (!StringUtils.hasText(line) || isSection(line)) {
        continue;
      }
      for (String alias : aliases) {
        int labelIndex = line.indexOf(alias);
        if (labelIndex < 0) {
          continue;
        }
        if (isEmbeddedAlias(line, labelIndex, alias)) {
          continue;
        }
        if (isShadowedByLongerLabel(line, labelIndex, alias, allLabels)) {
          continue;
        }
        int valueStart = skipSeparators(line, labelIndex + alias.length());
        String value = trimToNull(line.substring(valueStart, nextLabelIndex(line, valueStart, allLabels)));
        if (!StringUtils.hasText(value)) {
          value = nextLineValue(lines, lineIndex + 1, allLabels);
        }
        if (StringUtils.hasText(value)) {
          return new FieldValue(value, "PDF:line:" + (lineIndex + 1) + ":" + alias);
        }
      }
    }
    return null;
  }

  private void applyCoordinateNumberOverrides(
      QuotePdfParseContext context, QuoteIngestHeaderRequest header) {
    if (context == null
        || context.getDocument() == null
        || context.getTemplateDefinition() == null
        || header == null) {
      return;
    }
    for (QuoteOaPdfFieldDefinition field : context.getTemplateDefinition().getHeaderFields()) {
      if (!COORDINATE_NUMBER_FIELDS.contains(field.getFieldCode())) {
        continue;
      }
      String coordinateValue = findCoordinateNumberValue(context.getDocument(), field);
      String currentValue = getHeaderValue(header, field.getFieldCode());
      if (StringUtils.hasText(coordinateValue)) {
        setHeaderValue(header, field.getFieldCode(), coordinateValue);
      } else if (StringUtils.hasText(currentValue) && !isNumber(currentValue)) {
        setHeaderValue(header, field.getFieldCode(), null);
      }
    }
  }

  private void applyTextHeaderFallbacks(List<String> lines, QuoteIngestHeaderRequest header) {
    if (header == null || isBusinessDivisionValue(header.getSourceBusinessDivision())) {
      return;
    }
    String value = findBusinessDivisionValue(lines);
    if (StringUtils.hasText(value)) {
      header.setSourceBusinessDivision(value);
    }
  }

  private String findBusinessDivisionValue(List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return null;
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = trimToNull(lines.get(i));
      if (!isBusinessDivisionLabelLine(line)) {
        continue;
      }
      for (int j = i; j < lines.size() && j <= i + 3; j++) {
        String value = extractBusinessDivisionValue(lines.get(j));
        if (StringUtils.hasText(value)) {
          return value;
        }
      }
    }
    return null;
  }

  private boolean isBusinessDivisionLabelLine(String line) {
    String text = trimToNull(line);
    return text != null && text.startsWith("事业部");
  }

  private String extractBusinessDivisionValue(String line) {
    String text = trimToNull(line);
    if (text == null) {
      return null;
    }
    Matcher matcher = BUSINESS_DIVISION_VALUE_PATTERN.matcher(text);
    while (matcher.find()) {
      String candidate = trimToNull(matcher.group());
      if (isBusinessDivisionValue(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private boolean isBusinessDivisionValue(String value) {
    String text = trimToNull(value);
    return text != null
        && text.endsWith("事业部")
        && !"事业部".equals(text)
        && !text.startsWith("事业部")
        && !text.contains("多事业部")
        && !text.contains("型号多");
  }

  private String findCoordinateNumberValue(QuotePdfDocument document, QuoteOaPdfFieldDefinition field) {
    if (document.getPages() == null) {
      return null;
    }
    for (QuotePdfPage page : document.getPages()) {
      if (page == null || page.getLines() == null) {
        continue;
      }
      List<QuotePdfLine> lines = page.getLines();
      for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
        QuotePdfLine line = lines.get(lineIndex);
        List<QuotePdfToken> tokens = sortedTokens(line);
        for (QuotePdfToken token : tokens) {
          if (!isCoordinateNumberLabel(token.getText(), field.getFieldCode())) {
            continue;
          }
          String value = numberOnSameLine(tokens, token);
          if (StringUtils.hasText(value)) {
            return value;
          }
          value = numberOnFollowingValueLine(lines, lineIndex, token);
          if (StringUtils.hasText(value)) {
            return value;
          }
        }
      }
    }
    return null;
  }

  private String numberOnSameLine(List<QuotePdfToken> tokens, QuotePdfToken labelToken) {
    float left = leftBoundary(tokens, labelToken);
    float right = rightBoundary(tokens, labelToken);
    for (QuotePdfToken token : tokens) {
      if (token.getX() <= labelToken.getX() || token.getX() <= left || token.getX() >= right) {
        continue;
      }
      String value = normalizeNumberToken(token.getText());
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private String numberOnFollowingValueLine(
      List<QuotePdfLine> lines, int labelLineIndex, QuotePdfToken labelToken) {
    float left = leftBoundary(sortedTokens(lines.get(labelLineIndex)), labelToken);
    float right = rightBoundary(sortedTokens(lines.get(labelLineIndex)), labelToken);
    for (int i = labelLineIndex + 1; i < lines.size() && i <= labelLineIndex + 3; i++) {
      List<QuotePdfToken> tokens = sortedTokens(lines.get(i));
      if (tokens.isEmpty()) {
        continue;
      }
      float yGap = minY(tokens) - labelToken.getY();
      if (yGap > VALUE_LINE_Y_GAP) {
        return null;
      }
      if (containsCoordinateLabelInColumn(tokens, left, right)) {
        return null;
      }
      for (QuotePdfToken token : tokens) {
        if (token.getX() <= labelToken.getX() || token.getX() <= left || token.getX() >= right) {
          continue;
        }
        String value = normalizeNumberToken(token.getText());
        if (StringUtils.hasText(value)) {
          return value;
        }
      }
    }
    return null;
  }

  private float leftBoundary(List<QuotePdfToken> tokens, QuotePdfToken labelToken) {
    QuotePdfToken previousLabel = null;
    for (QuotePdfToken token : tokens) {
      if (token.getX() >= labelToken.getX()) {
        break;
      }
      if (isAnyCoordinateNumberLabel(token.getText())) {
        previousLabel = token;
      }
    }
    return previousLabel == null ? Float.NEGATIVE_INFINITY : midpoint(previousLabel, labelToken);
  }

  private float rightBoundary(List<QuotePdfToken> tokens, QuotePdfToken labelToken) {
    for (QuotePdfToken token : tokens) {
      if (token.getX() > labelToken.getX() && isAnyCoordinateNumberLabel(token.getText())) {
        return midpoint(labelToken, token);
      }
    }
    return Float.POSITIVE_INFINITY;
  }

  private float midpoint(QuotePdfToken left, QuotePdfToken right) {
    return (left.getX() + right.getX()) / 2f;
  }

  private boolean containsCoordinateLabelInColumn(List<QuotePdfToken> tokens, float left, float right) {
    for (QuotePdfToken token : tokens) {
      if (token.getX() > left && token.getX() < right && isAnyCoordinateNumberLabel(token.getText())) {
        return true;
      }
    }
    return false;
  }

  private List<QuotePdfToken> sortedTokens(QuotePdfLine line) {
    if (line == null || line.getTokens() == null) {
      return List.of();
    }
    List<QuotePdfToken> tokens = new ArrayList<>(line.getTokens());
    tokens.sort(Comparator.comparing(QuotePdfToken::getX));
    return tokens;
  }

  private float minY(List<QuotePdfToken> tokens) {
    float min = Float.MAX_VALUE;
    for (QuotePdfToken token : tokens) {
      min = Math.min(min, token.getY());
    }
    return min == Float.MAX_VALUE ? 0f : min;
  }

  private boolean isAnyCoordinateNumberLabel(String text) {
    for (String fieldCode : COORDINATE_NUMBER_FIELDS) {
      if (isCoordinateNumberLabel(text, fieldCode)) {
        return true;
      }
    }
    return false;
  }

  private boolean isCoordinateNumberLabel(String text, String fieldCode) {
    String value = trimToNull(text);
    if (value == null) {
      return false;
    }
    String upper = value.toUpperCase();
    return switch (fieldCode) {
      case "exchangeRate" -> value.equals("汇率") || value.endsWith("汇率");
      case "copperPrice" -> value.contains("铜") && (value.contains("基价") || value.contains("铜价"));
      case "zincPrice" -> value.contains("锌") && (value.contains("基价") || value.contains("锌价"));
      case "aluminumPrice" -> value.contains("铝") && (value.contains("基价") || value.contains("铝价"));
      case "steelPrice" -> value.contains("不锈钢") || (value.contains("钢") && value.contains("价"));
      case "silverPrice" -> value.contains("银") && value.contains("价");
      case "goldPrice" -> value.contains("金") && value.contains("价");
      case "sus304Price" -> upper.contains("SUS304");
      case "sus316lPrice" -> upper.contains("SUS316");
      case "baseShipping" -> value.contains("运费核算标准") || value.contains("海运费核算标准");
      default -> false;
    };
  }

  private String normalizeNumberToken(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.replace("，", ",");
    return isNumber(normalized) ? normalized : null;
  }

  private boolean isNumber(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return false;
    }
    if (normalized.endsWith("%")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.matches("-?\\d+(,\\d{3})*(\\.\\d+)?");
  }

  private boolean isEmbeddedAlias(String line, int labelIndex, String alias) {
    if (alias == null || alias.length() > 2) {
      return false;
    }
    int end = labelIndex + alias.length();
    return end < line.length() && isLabelContinuation(line.charAt(end));
  }

  private boolean isLabelContinuation(char ch) {
    return Character.isLetterOrDigit(ch)
        || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
  }

  private String nextLineValue(List<String> lines, int startIndex, List<String> allLabels) {
    for (int i = startIndex; i < lines.size(); i++) {
      String line = trimToNull(lines.get(i));
      if (!StringUtils.hasText(line)) {
        continue;
      }
      if (isSection(line) || startsWithAnyLabel(line, allLabels)) {
        return null;
      }
      return line;
    }
    return null;
  }

  private boolean startsWithAnyLabel(String line, List<String> allLabels) {
    if (!StringUtils.hasText(line)) {
      return false;
    }
    for (String label : allLabels) {
      if (line.startsWith(label)) {
        return true;
      }
    }
    return false;
  }

  private boolean isShadowedByLongerLabel(
      String line, int labelIndex, String alias, List<String> allLabels) {
    for (String label : allLabels) {
      if (label.length() > alias.length() && line.startsWith(label, labelIndex)) {
        return true;
      }
    }
    return false;
  }

  private int nextLabelIndex(String line, int valueStart, List<String> allLabels) {
    int next = line.length();
    for (String label : allLabels) {
      int index = line.indexOf(label, valueStart);
      while (index >= 0) {
        if (index < next && isStandaloneLabelBoundary(line, index, label, valueStart)) {
          next = index;
          break;
        }
        index = line.indexOf(label, index + Math.max(label.length(), 1));
      }
    }
    return next;
  }

  private boolean isStandaloneLabelBoundary(String line, int index, String label, int valueStart) {
    if (!StringUtils.hasText(line) || !StringUtils.hasText(label)) {
      return false;
    }
    if (isInsideParentheses(line, index, valueStart)) {
      return false;
    }
    int end = index + label.length();
    if (index > 0 && isLabelContinuation(line.charAt(index - 1))) {
      return false;
    }
    return end >= line.length() || !isLabelContinuation(line.charAt(end));
  }

  private boolean isInsideParentheses(String line, int index, int valueStart) {
    int depth = 0;
    for (int i = Math.max(0, valueStart); i < index && i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '（' || ch == '(') {
        depth++;
      } else if ((ch == '）' || ch == ')') && depth > 0) {
        depth--;
      }
    }
    return depth > 0;
  }

  private int skipSeparators(String line, int start) {
    int index = start;
    while (index < line.length()) {
      char ch = line.charAt(index);
      if (!Character.isWhitespace(ch) && ch != ':' && ch != '：' && ch != '-' && ch != '—') {
        break;
      }
      index++;
    }
    return index;
  }

  private List<String> allLabels(List<QuoteOaPdfFieldDefinition> fields) {
    Set<String> labels = new LinkedHashSet<>();
    for (QuoteOaPdfFieldDefinition field : fields) {
      labels.addAll(fieldLabels(field));
    }
    return labels.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
  }

  private List<String> fieldLabels(QuoteOaPdfFieldDefinition field) {
    List<String> labels = new ArrayList<>();
    if (StringUtils.hasText(field.getFieldName())) {
      labels.add(field.getFieldName());
    }
    labels.addAll(field.getAliases());
    return labels.stream()
        .filter(StringUtils::hasText)
        .distinct()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();
  }

  private List<String> lines(QuotePdfDocument document) {
    if (document == null) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    if (document.getPages() != null) {
      for (QuotePdfPage page : document.getPages()) {
        if (page == null || page.getLines() == null) {
          continue;
        }
        for (QuotePdfLine line : page.getLines()) {
          String text = line == null ? null : trimToNull(line.getText());
          if (text != null) {
            lines.add(text);
          }
        }
      }
    }
    if (!lines.isEmpty()) {
      return lines;
    }
    String fullText = document.getFullText();
    if (!StringUtils.hasText(fullText)) {
      return List.of();
    }
    for (String line : fullText.split("\\R")) {
      String text = trimToNull(line);
      if (text != null) {
        lines.add(text);
      }
    }
    return lines;
  }

  private QuoteExtraFieldRequest toExtraField(
      QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
    QuoteExtraFieldRequest extraField = new QuoteExtraFieldRequest();
    extraField.setFieldCode(field.getFieldCode());
    extraField.setFieldName(field.getFieldName());
    extraField.setFieldValue(value);
    extraField.setValueType(valueType(value));
    extraField.setSourceFieldName(field.getFieldName());
    extraField.setSourceFieldPath(sourcePath);
    return extraField;
  }

  private String valueType(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "TEXT";
    }
    if (normalized.matches("-?\\d+(,\\d{3})*(\\.\\d+)?")) {
      return "NUMBER";
    }
    if (normalized.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}.*")) {
      return "DATE";
    }
    return "TEXT";
  }

  private boolean isSection(String line) {
    return line != null && line.trim().startsWith(">>");
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String getHeaderValue(QuoteIngestHeaderRequest header, String fieldCode) {
    return switch (fieldCode) {
      case "exchangeRate" -> header.getExchangeRate();
      case "copperPrice" -> header.getCopperPrice();
      case "zincPrice" -> header.getZincPrice();
      case "aluminumPrice" -> header.getAluminumPrice();
      case "steelPrice" -> header.getSteelPrice();
      case "silverPrice" -> header.getSilverPrice();
      case "goldPrice" -> header.getGoldPrice();
      case "sus304Price" -> header.getSus304Price();
      case "sus316lPrice" -> header.getSus316lPrice();
      case "baseShipping" -> header.getBaseShipping();
      default -> null;
    };
  }

  private void setHeaderValue(QuoteIngestHeaderRequest header, String fieldCode, String value) {
    switch (fieldCode) {
      case "exchangeRate" -> header.setExchangeRate(value);
      case "copperPrice" -> header.setCopperPrice(value);
      case "zincPrice" -> header.setZincPrice(value);
      case "aluminumPrice" -> header.setAluminumPrice(value);
      case "steelPrice" -> header.setSteelPrice(value);
      case "silverPrice" -> header.setSilverPrice(value);
      case "goldPrice" -> header.setGoldPrice(value);
      case "sus304Price" -> header.setSus304Price(value);
      case "sus316lPrice" -> header.setSus316lPrice(value);
      case "baseShipping" -> header.setBaseShipping(value);
      default -> {
      }
    }
  }

  private record FieldValue(String value, String sourcePath) {}
}
