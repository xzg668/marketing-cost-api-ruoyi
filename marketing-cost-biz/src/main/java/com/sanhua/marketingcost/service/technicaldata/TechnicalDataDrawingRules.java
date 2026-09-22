package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingBom;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 技术提交只要求真实、完整的图库明细；U9 料号的人工确认由财务处理。 */
public final class TechnicalDataDrawingRules {
  private TechnicalDataDrawingRules() {}

  public static List<String> validate(DrawingBom drawing, QuoteTechProduct product) {
    var issues = new ArrayList<String>();
    if (drawing == null || drawing.sourceVersionId() == null || drawing.nodes() == null || drawing.nodes().isEmpty()) {
      return List.of("尚未取得有效电子图库明细，请维护后重新检查");
    }
    var source = drawing.evidence();
    if (source == null || !Objects.equals(source.oaFormItemId(), product.getOaFormItemId())
        || !Objects.equals(source.accountingMonth(), product.getAccountingMonth())
        || blank(source.drawingNo()) || source.acquiredAt() == null || blank(source.requestId())
        || source.fileSha256() == null || !source.fileSha256().matches("[0-9a-f]{64}")) {
      issues.add("图库来源证据与本产品或核算月份不一致，请重新检查");
    }
    Set<String> keys = new HashSet<>();
    Set<String> ids = new HashSet<>();
    for (var node : drawing.nodes()) {
      if (node == null || blank(node.itemKey()) || !keys.add(node.itemKey())
          || blank(node.sourceNodeId()) || !ids.add(node.sourceNodeId())
          || blank(node.drawingNo()) || blank(node.name())
          || node.quantityPerParent() == null || node.quantityPerParent().signum() <= 0) {
        issues.add("图库节点身份、图号、名称或数量不完整");
        continue;
      }
      if (node.sourceWeight() != null && (node.sourceWeight().signum() < 0
          || !Set.of("g", "kg").contains(Objects.toString(node.sourceWeightUnit(), "")))) {
        issues.add("图库重量或重量单位无效");
      }
    }
    if (drawing.nodes().stream().filter(Objects::nonNull).anyMatch(node -> !blank(node.parentItemKey())
        && (!keys.contains(node.parentItemKey()) || node.parentItemKey().equals(node.itemKey())))) {
      issues.add("图库父子节点不完整");
    }
    return List.copyOf(issues);
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
