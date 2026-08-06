package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/** 供货比例形态解析结果；成功时同时保存规则证据和主供应商比例快照。 */
public record SupplierRatioResolution(
    String materialOrganizationCode,
    String priceOrgCode,
    String materialCode,
    String accountingMonth,
    QuoteMaterialShape effectiveShape,
    Long policyId,
    String policyFingerprint,
    Long selectedRatioRecordId,
    String selectedSupplierCode,
    String selectedSupplierName,
    BigDecimal selectedSupplyRatio,
    Boolean internalSupplier,
    String conditionConfigJson,
    String actionConfigJson,
    String blockingReason) {

  public boolean blocked() {
    return effectiveShape == null || StringUtils.hasText(blockingReason);
  }
}
