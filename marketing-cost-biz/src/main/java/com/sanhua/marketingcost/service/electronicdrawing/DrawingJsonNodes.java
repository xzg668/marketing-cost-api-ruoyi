package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 从完整证据提取已经核实归属的产品组；原始九字段仍以不可变结果为准。 */
public final class DrawingJsonNodes {
  private DrawingJsonNodes() {}

  public static List<ElectronicDrawingSourceNode> forProduct(
      DrawingVerificationSummary summary, String drawingNo) {
    var drawing = summary.data().get(drawingNo);
    if (drawing == null || drawing.params().items().isEmpty()) {
      throw invalid("已接收明细中没有本产品图号的有效行：" + drawingNo);
    }
    int group = new ArrayList<>(summary.data().keySet()).indexOf(drawingNo) + 1;
    var nodes = new ArrayList<ElectronicDrawingSourceNode>();
    int position = 0;
    for (var row : drawing.params().items()) {
      int line = ++position;
      String location = "图号 " + drawingNo + " 第 " + line + " 行";
      var node = new ElectronicDrawingSourceNode();
      node.setSourceRowNo(line);
      // no 允许空值和重复；组位置+行位置标识原文中的准确位置，不解释为层级。
      node.setSourceSequence("G" + group + ":R" + line);
      node.setDrawingCode(required(row.code(), 128, location + "图号"));
      node.setSourceName(required(row.name(), 180, location + "名称"));
      node.setQty(decimal(row.qty(), true, location + "数量"));
      node.setMaterial(bounded(row.mat(), 255, location + "材料"));
      node.setImportanceClass(bounded(row.grade(), 64, location + "重要性"));
      node.setHsfRiskClass(bounded(row.hsf(), 64, location + "HSF 风险"));
      node.setReferenceWeight(
          row.weight() == null || row.weight().isBlank()
              ? null
              : decimal(row.weight(), false, location + "单重"));
      // PDF 合同没有重量单位，不能猜测 g/kg；需要重量计算时由现有资料规则核实单位。
      node.setReferenceWeightUnit(null);
      node.setSourceRemark(bounded(row.note(), 1000, location + "备注"));
      node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
      nodes.add(node);
    }
    return List.copyOf(nodes);
  }

  private static BigDecimal decimal(String value, boolean positive, String field) {
    if (value == null || value.isBlank()) throw invalid(field + "未填写，不能生成计算用量");
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.trim());
    } catch (NumberFormatException invalid) {
      throw invalid(field + "不是有效数字");
    }
    var normalized = parsed.stripTrailingZeros();
    if (parsed.signum() < 0 || positive && parsed.signum() == 0) {
      throw invalid(field + (positive ? "必须大于零" : "不能为负数"));
    }
    if (normalized.scale() > 8 || normalized.precision() - normalized.scale() > 12) {
      throw invalid(field + "超出计算精度，原文已保留，请核对后确认");
    }
    return parsed;
  }

  private static String required(String value, int limit, String field) {
    if (value == null || value.isBlank()) throw invalid(field + "未填写");
    return bounded(value, limit, field);
  }

  private static String bounded(String value, int limit, String field) {
    if (value != null && value.codePointCount(0, value.length()) > limit)
      throw invalid(field + "超出业务字段长度，完整原文已保留");
    return value;
  }

  private static ElectronicDrawingSourceImportException invalid(String message) {
    return new ElectronicDrawingSourceImportException(
        ElectronicDrawingSourceImportException.SOURCE_INVALID, message);
  }
}
