package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioImportResponse {
  private int totalRows;
  private int insertedRows;
  private int updatedRows;
  private int skippedRows;
  private int errorRows;
  private String batchNo;
  private final List<String> errors = new ArrayList<>();
}
