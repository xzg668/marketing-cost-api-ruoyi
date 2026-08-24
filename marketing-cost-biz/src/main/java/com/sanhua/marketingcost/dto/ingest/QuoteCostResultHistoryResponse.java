package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuoteCostResultHistoryResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private List<QuoteCostResultHistoryItemResponse> results = new ArrayList<>();
}
