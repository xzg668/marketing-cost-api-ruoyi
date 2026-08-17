package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一次扫描内冻结的报价、月份和组织上下文。 */
public record QuoteCollaborationScanContext(
    Long oaFormId,
    Long oaFormItemId,
    String oaNo,
    String accountingMonth,
    String businessUnitType,
    String productCode,
    String productName,
    String productSpec,
    String productModel,
    String priceOrgCode,
    String materialOrganizationCode,
    LocalDate bomEffectiveDate,
    LocalDateTime scanAt) {

  public QuoteDataOrganization organization() {
    return new QuoteDataOrganization(priceOrgCode, materialOrganizationCode);
  }
}
