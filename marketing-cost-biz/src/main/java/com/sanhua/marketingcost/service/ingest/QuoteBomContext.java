package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;

/** 当前 OA 产品行读取和复用 BOM 时的唯一上下文。 */
public record QuoteBomContext(
    String costPeriodMonth,
    String productCode,
    ResolvedCustomerKey customer,
    String packageMethod,
    QuoteDataOrganization organization) {

  public String customerKey() {
    return customer.value();
  }
}
