package com.sanhua.marketingcost.dto.quotecosting;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuotePriceTypeConfirmationActionResponse {
  private String confirmNo;
  private String status;
  private QuotePriceTypeConfirmationSummary summary;
  private List<RowResult> results = new ArrayList<>();

  @Data
  public static class RowResult {
    private String materialCode;
    private String status;
    private String message;

    public static RowResult of(String materialCode, String status, String message) {
      RowResult result = new RowResult();
      result.setMaterialCode(materialCode);
      result.setStatus(status);
      result.setMessage(message);
      return result;
    }
  }
}
