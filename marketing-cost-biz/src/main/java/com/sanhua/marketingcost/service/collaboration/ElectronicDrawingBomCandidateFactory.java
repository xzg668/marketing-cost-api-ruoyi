package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将纯 Excel 行挂到当前报价产品根节点，形成与未来电子图库接口一致的候选树。 */
@Component
public class ElectronicDrawingBomCandidateFactory {

  public ElectronicDrawingBomCandidate create(
      ElectronicDrawingExcelParseResult parsed,
      ElectronicDrawingBomCandidate.RootProduct root) {
    if (parsed == null || !parsed.valid()) {
      throw new IllegalArgumentException("电子图库 Excel 尚未通过解析，不能生成候选BOM");
    }
    if (root == null || !hasText(firstText(root.productCode(), root.temporaryProductKey()))) {
      throw new IllegalArgumentException("当前报价产品缺少稳定的根节点标识");
    }

    List<ElectronicDrawingBomCandidate.Node> nodes = new ArrayList<>();
    nodes.add(new ElectronicDrawingBomCandidate.Node(
        "ROOT", null, 0, null, trim(root.productCode()), trim(root.drawingNo()),
        trim(root.productName()), trim(root.productSpec()), trim(root.productModel()),
        trim(root.materialNature()), trim(root.unit()), BigDecimal.ONE, null,
        null, null, null, null, null));
    for (ElectronicDrawingExcelParseResult.SourceNode row : parsed.nodes()) {
      nodes.add(new ElectronicDrawingBomCandidate.Node(
          nodeKey(row.sourceSequence()),
          row.parentSourceSequence() == null ? "ROOT" : nodeKey(row.parentSourceSequence()),
          row.level(), row.sourceSequence(), null, row.drawingCode(), row.sourceName(),
          null, null, null, null, row.quantity(), row.referenceWeight(), row.sourceMaterial(),
          row.importanceClass(), row.hsfRiskClass(), row.remark(), row.sourceRowNumber()));
    }
    return new ElectronicDrawingBomCandidate(
        parsed.sourceFileName(), parsed.sourceSheetName(), nodes);
  }

  private static String nodeKey(String sequence) {
    return "ED-" + sequence;
  }

  private static String firstText(String first, String second) {
    return hasText(first) ? first : second;
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String trim(String value) {
    return hasText(value) ? value.trim() : null;
  }
}
