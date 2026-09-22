package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse.CalculationInput;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 单件输入、来源绑定与本期一件一原料规则；不重新实现成本公式。 */
public final class TechnicalDataManufacturingRules {
  private TechnicalDataManufacturingRules() {}

  public static List<String> validate(Manufacturing content, QuoteTechProduct product) {
    var issues = new ArrayList<String>();
    if (content == null || content.items() == null || content.evidence() == null) {
      return List.of("制造件原材料尚未保存完整的来源检查结果");
    }
    var scope = content.evidence();
    if (!Objects.equals(scope.oaFormItemId(), product.getOaFormItemId())
        || !Objects.equals(scope.accountingMonth(), product.getAccountingMonth())
        || scope.drawingSourceVersionId() == null || scope.checkedAt() == null
        || scope.sourceFingerprint() == null || !scope.sourceFingerprint().matches("[0-9a-f]{64}")) {
      issues.add("制造件来源与本产品或月份不一致，请重新检查");
    }
    var parents = new HashSet<String>();
    var keys = new HashSet<String>();
    for (var row : content.items()) {
      if (row == null || blank(row.itemKey()) || !keys.add(row.itemKey()) || blank(row.parentSourceNodeId())
          || !parents.add(row.parentSourceNodeId())) {
        issues.add("本期一个制造件只能补一种原材料，不能重复或缺少来源节点");
        continue;
      }
      if (blank(row.parentMaterialNo()) || blank(row.rawMaterialNo())
          || !Objects.equals(scope.drawingSourceVersionId(), row.sourceVersionId())) {
        issues.add("制造件或原材料的料号、来源版本不完整");
      }
      try {
        var input = calculationInput(row);
        if (input.netWeightG().signum() <= 0 || input.grossWeightG().compareTo(input.netWeightG()) < 0) {
          issues.add("单件净重必须大于 0，毛重不能小于图库净重");
        }
        if (row.netLengthMm() == null || row.netLengthMm().signum() <= 0) issues.add("产品净长必须大于 0 mm");
        if (row.netWeightKg() == null || input.netWeightG().compareTo(row.netWeightKg().movePointRight(3)) != 0
            || row.quantityPerParent() == null || input.purchasingQuantity().compareTo(row.quantityPerParent()) != 0) {
          issues.add("制造件净重或采购用量与来源单位不一致");
        }
      } catch (IllegalArgumentException error) { issues.add(error.getMessage()); }
    }
    return List.copyOf(issues);
  }

  public static CalculationInput calculationInput(RawMaterial row) {
    if (row.evidence() == null) throw new IllegalArgumentException("制造件缺少原始图库重量证据");
    BigDecimal net = TechnicalDataManufacturingUnits.sourceWeightG(
        row.evidence().sourceNetWeight(), row.evidence().sourceNetWeightUnit());
    return new CalculationInput(row.parentSourceNodeId(), row.rawMaterialNo(),
        TechnicalDataManufacturingUnits.grossWeightG(row.grossWeightKg()), net, row.netLengthMm(),
        TechnicalDataManufacturingUnits.purchasingQuantity(row.grossWeightKg(), row.unit()), row.unit());
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
