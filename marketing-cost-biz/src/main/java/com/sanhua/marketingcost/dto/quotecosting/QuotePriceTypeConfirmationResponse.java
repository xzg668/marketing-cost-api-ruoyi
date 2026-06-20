package com.sanhua.marketingcost.dto.quotecosting;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuotePriceTypeConfirmationResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String bomConfirmNo;
  private QuotePriceTypeConfirmationSummary summary;
  private List<QuotePriceTypeConfirmationRow> rows = new ArrayList<>();
}
