package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioImportResponse {
  private int totalRows;
  private int insertedRows;
  private int deactivatedRows;
  private int duplicateRows;
  private int unfilledRatioRows;
  private String batchNo;
}
