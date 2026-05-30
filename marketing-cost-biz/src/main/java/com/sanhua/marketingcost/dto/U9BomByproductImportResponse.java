package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class U9BomByproductImportResponse {
  private String datasetCode;
  private String sourceType;
  private int totalCount;
  private int successCount;
  private int failCount;
  private String status;
  private String message;
  private List<RowError> errors = new ArrayList<>();

  @Getter
  @Setter
  public static class RowError {
    private Integer excelRow;
    private String parentMaterialNo;
    private String byproductMaterialNo;
    private String reason;

    public RowError() {}

    public RowError(
        Integer excelRow, String parentMaterialNo, String byproductMaterialNo, String reason) {
      this.excelRow = excelRow;
      this.parentMaterialNo = parentMaterialNo;
      this.byproductMaterialNo = byproductMaterialNo;
      this.reason = reason;
    }
  }
}
