package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

public class QuoteOaPdfDetailTableParser {
  private static final String TABLE_FEE = "lp_oa_form_extra_fee";

  public void parse(QuotePdfParseContext context, QuoteIngestRequest request) {
    if (context == null || context.getDocument() == null || context.getTemplateDefinition() == null || request == null) {
      return;
    }
    QuoteOaPdfTableDefinition table = context.getTemplateDefinition().getItemTable();
    if (table == null) {
      return;
    }

    List<TableLine> lines = tableLines(context.getDocument());
    List<QuoteOaPdfFieldDefinition> fields = tableFields(context.getTemplateDefinition());
    TableRange range = tableRange(lines, table, fields);
    if (range == null || range.startIndex() + 1 >= range.endIndex()) {
      return;
    }

    List<Column> columns = inferColumns(lines.subList(range.startIndex() + 1, range.endIndex()), fields);
    if (columns.isEmpty()) {
      return;
    }

    int headerEndIndex = columns.stream().mapToInt(Column::lineIndex).max().orElse(range.startIndex());
    List<RowDraft> rows = readRows(lines, range, columns, headerEndIndex);
    for (RowDraft row : rows) {
      QuoteIngestItemRequest item = toItem(row, context.getTemplateDefinition());
      if (item == null) {
        continue;
      }
      request.getItems().add(item);
    }
  }

  private List<QuoteOaPdfFieldDefinition> tableFields(QuoteOaPdfTemplateDefinition template) {
    List<QuoteOaPdfFieldDefinition> fields = new ArrayList<>();
    fields.addAll(template.getItemFields());
    fields.addAll(template.getFeeFields());
    return fields;
  }

  private TableRange tableRange(
      List<TableLine> lines, QuoteOaPdfTableDefinition table, List<QuoteOaPdfFieldDefinition> fields) {
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (containsAny(lines.get(i).text(), table.getStartAnchors())) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      for (int i = 0; i < lines.size(); i++) {
        if (matchingHeaderColumnCount(lines.get(i), fields) >= 3) {
          start = Math.max(0, i - 1);
          break;
        }
      }
    }
    if (start < 0) {
      return null;
    }
    int end = lines.size();
    for (int i = start + 1; i < lines.size(); i++) {
      if (containsAny(lines.get(i).text(), table.getEndAnchors())) {
        end = i;
        break;
      }
    }
    return new TableRange(start, end);
  }

  private int matchingHeaderColumnCount(TableLine line, List<QuoteOaPdfFieldDefinition> fields) {
    Set<String> matched = new LinkedHashSet<>();
    for (QuotePdfToken token : line.tokens()) {
      QuoteOaPdfFieldDefinition field = matchField(token.getText(), fields);
      if (field != null) {
        matched.add(field.getFieldCode());
      }
    }
    return matched.size();
  }

  private List<Column> inferColumns(List<TableLine> lines, List<QuoteOaPdfFieldDefinition> fields) {
    Map<String, Column> byFieldCode = new LinkedHashMap<>();
    for (TableLine line : lines) {
      for (QuotePdfToken token : line.tokens()) {
        String text = trimToNull(token.getText());
        if (text == null) {
          continue;
        }
        QuoteOaPdfFieldDefinition field = matchField(text, fields);
        if (field == null || byFieldCode.containsKey(field.getFieldCode())) {
          continue;
        }
        byFieldCode.put(field.getFieldCode(), new Column(field, token.getX(), token.getWidth(), line.index()));
      }
    }
    List<Column> columns = new ArrayList<>(byFieldCode.values());
    columns.sort(Comparator.comparing(Column::x));
    return columns;
  }

  private List<RowDraft> readRows(
      List<TableLine> lines, TableRange range, List<Column> columns, int headerEndIndex) {
    List<RowDraft> rows = new ArrayList<>();
    RowDraft current = null;
    for (int i = headerEndIndex + 1; i < range.endIndex(); i++) {
      TableLine line = lines.get(i);
      if (!StringUtils.hasText(line.text()) || line.text().contains("暂无数据")) {
        continue;
      }
      Map<Column, String> values = valuesByColumn(line, columns);
      if (values.isEmpty() || isHeaderLike(values)) {
        continue;
      }
      String seq = values.entrySet().stream()
          .filter(entry -> "seq".equals(entry.getKey().field().getFieldCode()))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
      if (parseSeq(seq) != null) {
        current = new RowDraft();
        rows.add(current);
      } else if (current == null) {
        continue;
      }
      for (Map.Entry<Column, String> entry : values.entrySet()) {
        String fieldCode = entry.getKey().field().getFieldCode();
        if ("seq".equals(fieldCode) && parseSeq(entry.getValue()) == null) {
          continue;
        }
        current.append(entry.getKey().field(), entry.getValue(), sourcePath(line, entry.getKey().field()));
      }
    }
    return rows;
  }

  private Map<Column, String> valuesByColumn(TableLine line, List<Column> columns) {
    Map<Column, List<QuotePdfToken>> tokensByColumn = new LinkedHashMap<>();
    for (QuotePdfToken token : line.tokens()) {
      Column column = columnForToken(token, columns);
      if (column != null) {
        tokensByColumn.computeIfAbsent(column, ignored -> new ArrayList<>()).add(token);
      }
    }
    Map<Column, String> values = new LinkedHashMap<>();
    for (Map.Entry<Column, List<QuotePdfToken>> entry : tokensByColumn.entrySet()) {
      String value = joinTokens(entry.getValue());
      if (StringUtils.hasText(value)) {
        values.put(entry.getKey(), value);
      }
    }
    return values;
  }

  private Column columnForToken(QuotePdfToken token, List<Column> columns) {
    if (token == null || columns.isEmpty()) {
      return null;
    }
    float center = token.getX() + token.getWidth() / 2f;
    for (int i = 0; i < columns.size(); i++) {
      float left =
          i == 0 ? Float.NEGATIVE_INFINITY : midpoint(columns.get(i - 1), columns.get(i));
      float right =
          i == columns.size() - 1 ? Float.POSITIVE_INFINITY : midpoint(columns.get(i), columns.get(i + 1));
      if (center >= left && center < right) {
        return columns.get(i);
      }
    }
    return columns.get(columns.size() - 1);
  }

  private float midpoint(Column left, Column right) {
    return (left.x() + right.x()) / 2f;
  }

  private QuoteIngestItemRequest toItem(RowDraft row, QuoteOaPdfTemplateDefinition template) {
    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    for (Map.Entry<String, CellValue> entry : row.valuesByFieldCode().entrySet()) {
      CellValue cell = entry.getValue();
      if (!StringUtils.hasText(cell.value())) {
        continue;
      }
      if (isFeeField(cell.field())) {
        item.getExtraFees().add(toFee(cell.field(), cell.value(), cell.sourcePath()));
      } else if (!QuoteIngestFieldBinder.applyItemField(item, cell.field().getFieldCode(), cell.value())) {
        item.getExtraFields().add(toExtraField(cell.field(), cell.value(), cell.sourcePath()));
      }
    }
    if (!StringUtils.hasText(item.getBusinessType()) && StringUtils.hasText(template.getDefaultBusinessType())) {
      item.setBusinessType(template.getDefaultBusinessType());
    }
    if (item.getSeq() != null) {
      item.setExternalLineId("PDF:item:" + item.getSeq());
    }
    return isBlankItem(item) ? null : item;
  }

  private boolean isFeeField(QuoteOaPdfFieldDefinition field) {
    return TABLE_FEE.equals(field.getTargetTable())
        || QuoteIngestFeeFieldClassifier.isFeeField(field.getFieldCode(), field.getFieldName());
  }

  private QuoteExtraFeeRequest toFee(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setFeeCode(field.getFieldCode());
    fee.setFeeName(field.getFieldName());
    fee.setFeeCategory(QuoteIngestFeeFieldClassifier.feeCategory(field.getFieldCode(), field.getFieldName()));
    fee.setAmount(value);
    fee.setUnit(QuoteIngestFeeFieldClassifier.unit(field.getFieldName()));
    fee.setSourceFieldName(field.getFieldName());
    fee.setSourceFieldPath(sourcePath);
    return fee;
  }

  private QuoteExtraFieldRequest toExtraField(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
    QuoteExtraFieldRequest extraField = new QuoteExtraFieldRequest();
    extraField.setFieldCode(field.getFieldCode());
    extraField.setFieldName(field.getFieldName());
    extraField.setFieldValue(value);
    extraField.setValueType(valueType(value));
    extraField.setSourceFieldName(field.getFieldName());
    extraField.setSourceFieldPath(sourcePath);
    return extraField;
  }

  private boolean isBlankItem(QuoteIngestItemRequest item) {
    return item.getSeq() == null
        && !StringUtils.hasText(item.getMaterialNo())
        && !StringUtils.hasText(item.getSunlModel())
        && !StringUtils.hasText(item.getProductName())
        && !StringUtils.hasText(item.getCustomerCode());
  }

  private boolean isHeaderLike(Map<Column, String> values) {
    for (Map.Entry<Column, String> entry : values.entrySet()) {
      if (!isLabelMatch(entry.getValue(), entry.getKey().field())) {
        return false;
      }
    }
    return true;
  }

  private QuoteOaPdfFieldDefinition matchField(String text, List<QuoteOaPdfFieldDefinition> fields) {
    return fields.stream()
        .filter(field -> isLabelMatch(text, field))
        .sorted(
            Comparator.comparingInt((QuoteOaPdfFieldDefinition field) -> longestLabel(field).length()).reversed())
        .findFirst()
        .orElse(null);
  }

  private boolean isLabelMatch(String text, QuoteOaPdfFieldDefinition field) {
    String normalizedText = normalizeLabel(text);
    if (!StringUtils.hasText(normalizedText)) {
      return false;
    }
    for (String label : labels(field)) {
      String normalizedLabel = normalizeLabel(label);
      if (StringUtils.hasText(normalizedLabel)
          && (normalizedText.equals(normalizedLabel) || normalizedText.contains(normalizedLabel))) {
        return true;
      }
    }
    return false;
  }

  private List<String> labels(QuoteOaPdfFieldDefinition field) {
    Set<String> labels = new LinkedHashSet<>();
    labels.add(field.getFieldName());
    labels.addAll(field.getAliases());
    return labels.stream().filter(StringUtils::hasText).toList();
  }

  private String longestLabel(QuoteOaPdfFieldDefinition field) {
    return labels(field).stream().max(Comparator.comparingInt(String::length)).orElse("");
  }

  private List<TableLine> tableLines(QuotePdfDocument document) {
    if (document == null || document.getPages() == null) {
      return List.of();
    }
    List<TableLine> lines = new ArrayList<>();
    for (QuotePdfPage page : document.getPages()) {
      if (page == null || page.getLines() == null) {
        continue;
      }
      for (int i = 0; i < page.getLines().size(); i++) {
        QuotePdfLine line = page.getLines().get(i);
        if (line == null) {
          continue;
        }
        List<QuotePdfToken> tokens = line.getTokens() == null ? List.of() : new ArrayList<>(line.getTokens());
        tokens.sort(Comparator.comparing(QuotePdfToken::getX));
        lines.add(new TableLine(lines.size(), page.getPageIndex(), i, nullToEmpty(line.getText()), tokens));
      }
    }
    return lines;
  }

  private String joinTokens(List<QuotePdfToken> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }
    List<QuotePdfToken> sorted = new ArrayList<>(tokens);
    sorted.sort(Comparator.comparing(QuotePdfToken::getX));
    StringBuilder builder = new StringBuilder();
    QuotePdfToken previous = null;
    for (QuotePdfToken token : sorted) {
      if (builder.length() > 0 && shouldInsertSpace(previous, token)) {
        builder.append(' ');
      }
      builder.append(token.getText());
      previous = token;
    }
    return trimToNull(builder.toString());
  }

  private boolean shouldInsertSpace(QuotePdfToken previous, QuotePdfToken current) {
    if (previous == null || current == null) {
      return false;
    }
    return current.getX() - (previous.getX() + previous.getWidth()) > 2.0f;
  }

  private String sourcePath(TableLine line, QuoteOaPdfFieldDefinition field) {
    return "PDF:page:" + (line.pageIndex() + 1) + ":line:" + (line.pageLineIndex() + 1) + ":" + field.getFieldName();
  }

  private boolean containsAny(String text, List<String> anchors) {
    if (!StringUtils.hasText(text) || anchors == null) {
      return false;
    }
    for (String anchor : anchors) {
      if (StringUtils.hasText(anchor) && text.contains(anchor)) {
        return true;
      }
    }
    return false;
  }

  private Integer parseSeq(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    if (!normalized.matches("\\d+(\\.0)?")) {
      return null;
    }
    try {
      return Integer.valueOf(normalized.replace(".0", ""));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String normalizeLabel(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "";
    }
    return normalized
        .replaceAll("[\\s:：,，.。()（）/\\\\\\-—_\\[\\]【】]", "")
        .toLowerCase(Locale.ROOT);
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

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record TableRange(int startIndex, int endIndex) {}

  private record TableLine(
      int index, int pageIndex, int pageLineIndex, String text, List<QuotePdfToken> tokens) {}

  private record Column(QuoteOaPdfFieldDefinition field, float x, float width, int lineIndex) {}

  private record CellValue(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {}

  private static final class RowDraft {
    private final Map<String, CellValue> valuesByFieldCode = new LinkedHashMap<>();

    private void append(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
      if (!StringUtils.hasText(value)) {
        return;
      }
      valuesByFieldCode.compute(
          field.getFieldCode(),
          (ignored, existing) -> {
            if (existing == null || !StringUtils.hasText(existing.value())) {
              return new CellValue(field, value.trim(), sourcePath);
            }
            if ("seq".equals(field.getFieldCode())) {
              return existing;
            }
            return new CellValue(field, existing.value() + " " + value.trim(), existing.sourcePath());
          });
    }

    private Map<String, CellValue> valuesByFieldCode() {
      return valuesByFieldCode;
    }
  }
}
