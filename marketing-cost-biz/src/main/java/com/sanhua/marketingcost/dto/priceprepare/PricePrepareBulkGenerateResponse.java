package com.sanhua.marketingcost.dto.priceprepare;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareBulkGenerateResponse {
  private int totalCount;
  private int successCount;
  private int failedCount;
  private List<PricePrepareBulkGenerateResult> records = new ArrayList<>();
}
