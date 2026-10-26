package com.sanhua.marketingcost.entity.monthly;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Unmodified business fields from the ODS order/tier snapshot, including trace-only dates. */
@Getter
@Setter
public final class OdsMaterialPriceRow {
  private Long id;
  private String companyName;
  private String materialCode;
  private String materialName;
  private String spec;
  private String supplierCode;
  private String supplierName;
  private String unit;
  private BigDecimal price;
  private String effDate;
  private String expDate;
  private String source;
  private String orderNo;
  private String orderLineNo;
  private BigDecimal jietiDown;
  private BigDecimal jietiUp;
  private String dt;
  private LocalDateTime syncedAt;
}
