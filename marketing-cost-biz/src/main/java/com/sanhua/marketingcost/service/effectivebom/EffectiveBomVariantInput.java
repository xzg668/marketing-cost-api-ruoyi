package com.sanhua.marketingcost.service.effectivebom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 计算最终有效 BOM 结果指纹所需的全部业务内容，不包含 OA、客户和创建人。 */
public record EffectiveBomVariantInput(
    String costPeriodMonth,
    String sourceBomBatchId,
    String priceOrgCode,
    String topProductCode,
    String packageMethod,
    Map<String, String> selectedMaterialCodeByGroupKey,
    EffectiveBomBuildResult buildResult) {

  public EffectiveBomVariantInput {
    selectedMaterialCodeByGroupKey =
        selectedMaterialCodeByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(selectedMaterialCodeByGroupKey));
  }
}
