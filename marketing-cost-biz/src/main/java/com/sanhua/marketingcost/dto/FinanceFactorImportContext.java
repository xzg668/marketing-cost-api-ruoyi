package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import java.util.Locale;

/** 业务单元来自登录上下文；组织、月份及默认方案是这次发布的显式输入。 */
public record FinanceFactorImportContext(
    String businessUnitType,
    String priceOrgCode,
    String priceMonth,
    String sheetName,
    String defaultSchemeCode) {
  public FinanceFactorImportContext {
    if (businessUnitType == null) throw new IllegalArgumentException("当前业务单元不能为空");
    businessUnitType = businessUnitType.trim().toUpperCase(Locale.ROOT);
    if (!businessUnitType.equals("COMMERCIAL") && !businessUnitType.equals("HOUSEHOLD")) {
      throw new IllegalArgumentException("业务单元仅支持COMMERCIAL/HOUSEHOLD");
    }
    priceOrgCode = MaterialOrganization.fromPriceOrgCode(priceOrgCode).getPriceOrgCode();
    priceMonth = FactorPriceScope.month(priceMonth);
    sheetName = sheetName == null || sheetName.isBlank() ? null : sheetName.trim();
    defaultSchemeCode =
        defaultSchemeCode == null || defaultSchemeCode.isBlank()
            ? null
            : defaultSchemeCode.trim().toUpperCase(Locale.ROOT);
  }
}
