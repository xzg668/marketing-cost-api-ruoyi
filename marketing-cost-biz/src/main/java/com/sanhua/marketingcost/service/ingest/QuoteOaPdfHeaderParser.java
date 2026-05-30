package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

public class QuoteOaPdfHeaderParser {
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
      if (index >= 0 && index < next) {
        next = index;
      }
    }
    return next;
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

  private record FieldValue(String value, String sourcePath) {}
}
