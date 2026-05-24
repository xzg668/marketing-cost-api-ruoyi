package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class U9MaterialImportResponse {
  private String batchNo;
  private String datasetCode;
  private String sourceType;
  private String mappingVersion;
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
    private String materialCode;
    private String reason;

    public RowError() {}

    public RowError(Integer excelRow, String materialCode, String reason) {
      this.excelRow = excelRow;
      this.materialCode = materialCode;
      this.reason = reason;
    }
  }
}
