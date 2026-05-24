package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioImportRow {
  private Integer rowNo;
  private String materialCode;
  private String materialName;
  private String specModel;
  private String unit;
  private String materialShape;
  private String supplierName;
  private String supplierCode;
  private BigDecimal supplyRatio;
  private String dedupeKey;
}
