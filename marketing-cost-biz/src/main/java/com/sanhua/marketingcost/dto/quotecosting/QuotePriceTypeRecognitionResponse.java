package com.sanhua.marketingcost.dto.quotecosting;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuotePriceTypeRecognitionResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String bomBuildBatchId;
  private QuotePriceTypeRecognitionSummary summary;
  private List<QuotePriceTypeRecognitionRow> rows = new ArrayList<>();
}
