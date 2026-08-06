package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import org.springframework.util.StringUtils;

/**
 * 单节点形态解析结果及证据。
 *
 * <p>阻断时 effectiveShape 为空，调用方必须停止后续最终树生成，不能自行猜测形态。
 */
public record MaterialQuoteShapeResolution(
    String materialOrgCode,
    String materialCode,
    String accountingMonth,
    String sourceU9Shape,
    QuoteMaterialShape normalizedU9Shape,
    QuoteMaterialShape effectiveShape,
    MaterialQuoteShapeSource source,
    Long policyId,
    String policyFingerprint,
    String conditionConfigJson,
    String actionConfigJson,
    String blockingReason) {

  public boolean blocked() {
    return effectiveShape == null || StringUtils.hasText(blockingReason);
  }
}
