package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 在现有 BOM 明细 remark 中保存最小来源证据，避免新增一套 Excel 明细表。 */
final class ElectronicDrawingSourceMetadataCodec {
  private static final String ROOT = "EDX1|ROOT";
  private static final String NODE = "EDX1|NODE";

  String root(String fileName, String sha256, String sheetName) {
    return ROOT + "|F=" + encode(fileName) + "|H=" + value(sha256) + "|S=" + encode(sheetName);
  }

  String node(
      ElectronicDrawingExcelParseResult.SourceNode source,
      ElectronicDrawingMaterialMatcher.Status initialStatus) {
    return NODE
        + "|Q=" + encode(source.sourceSequence())
        + "|R=" + source.sourceRowNumber()
        + "|MS=" + value(initialStatus == null ? null : initialStatus.name())
        + "|W=" + value(source.referenceWeight())
        + "|SM=" + encode(source.sourceMaterial())
        + "|I=" + encode(source.importanceClass())
        + "|HSF=" + encode(source.hsfRiskClass())
        + "|N=" + encode(source.remark());
  }

  RootMetadata decodeRoot(String remark) {
    if (remark == null || !remark.startsWith(ROOT)) return null;
    Map<String, String> values = values(remark);
    return new RootMetadata(decode(values.get("F")), values.get("H"), decode(values.get("S")));
  }

  NodeMetadata decodeNode(String remark) {
    if (remark == null || !remark.startsWith(NODE)) return null;
    Map<String, String> values = values(remark);
    Integer row = integer(values.get("R"));
    BigDecimal weight = decimal(values.get("W"));
    return new NodeMetadata(decode(values.get("Q")), row, values.get("MS"), weight,
        decode(values.get("SM")), decode(values.get("I")), decode(values.get("HSF")),
        decode(values.get("N")));
  }

  private Map<String, String> values(String remark) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String part : remark.split("\\|")) {
      int index = part.indexOf('=');
      if (index > 0) result.put(part.substring(0, index), part.substring(index + 1));
    }
    return result;
  }

  private String encode(String value) {
    if (value == null || value.isBlank()) return "";
    // remark 现有长度为 500；各来源字段只保留核对所需前 48 个字符，避免导入长备注导致落库失败。
    String safe = value.trim().length() > 48 ? value.trim().substring(0, 48) : value.trim();
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(safe.getBytes(StandardCharsets.UTF_8));
  }

  private String decode(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String value(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Integer integer(String value) {
    try { return value == null || value.isBlank() ? null : Integer.valueOf(value); }
    catch (NumberFormatException exception) { return null; }
  }

  private static BigDecimal decimal(String value) {
    try { return value == null || value.isBlank() ? null : new BigDecimal(value); }
    catch (NumberFormatException exception) { return null; }
  }

  record RootMetadata(String fileName, String sha256, String sheetName) {}

  record NodeMetadata(
      String sourceSequence,
      Integer sourceRowNumber,
      String initialMatchStatus,
      BigDecimal referenceWeight,
      String sourceMaterial,
      String importanceClass,
      String hsfRiskClass,
      String sourceRemark) {}
}
