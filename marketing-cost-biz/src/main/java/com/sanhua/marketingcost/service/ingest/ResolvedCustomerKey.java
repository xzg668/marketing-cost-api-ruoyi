package com.sanhua.marketingcost.service.ingest;

import org.springframework.util.StringUtils;

/** 报价 BOM 的客户隔离键，以及该键的可信来源。 */
public record ResolvedCustomerKey(String value, Source source, String warning) {
  public enum Source {
    VERIFIED_CUSTOMER_CODE,
    OA_HEADER_CUSTOMER,
    OA_NUMBER_FALLBACK
  }

  public ResolvedCustomerKey {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("客户隔离键不能为空");
    }
    if (source == null) {
      throw new IllegalArgumentException("客户隔离键来源不能为空");
    }
  }

  public boolean hasWarning() {
    return StringUtils.hasText(warning);
  }
}
