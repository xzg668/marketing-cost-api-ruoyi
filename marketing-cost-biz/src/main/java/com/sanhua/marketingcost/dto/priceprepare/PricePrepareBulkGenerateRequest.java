package com.sanhua.marketingcost.dto.priceprepare;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareBulkGenerateRequest {
  private List<String> oaNos;
  private List<PricePrepareGenerateTarget> targets;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
}
