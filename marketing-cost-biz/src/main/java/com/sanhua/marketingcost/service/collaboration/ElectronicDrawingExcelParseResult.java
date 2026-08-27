package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;
import java.util.List;

/** 电子图库正式 Excel 的纯解析结果；不包含 U9 料号匹配或任何落库行为。 */
public record ElectronicDrawingExcelParseResult(
    String sourceFileName,
    String sourceSheetName,
    List<SourceNode> nodes,
    List<Issue> issues) {

  public ElectronicDrawingExcelParseResult {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public boolean valid() {
    return issues.isEmpty();
  }

  /** Excel 中的一条真实明细；代号是图号，materialCode 要在下一阶段匹配 U9 后才能确定。 */
  public record SourceNode(
      String sourceSequence,
      String parentSourceSequence,
      int level,
      String drawingCode,
      String sourceName,
      String sourceMaterial,
      String importanceClass,
      String hsfRiskClass,
      BigDecimal quantity,
      BigDecimal referenceWeight,
      String remark,
      int sourceRowNumber) {}

  public record Issue(
      String code,
      Integer sourceRowNumber,
      String sourceSequence,
      String message) {}
}
