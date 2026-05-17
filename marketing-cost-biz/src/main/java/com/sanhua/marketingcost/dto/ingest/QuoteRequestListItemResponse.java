package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDate;
import lombok.Data;

@Data
public class QuoteRequestListItemResponse {
  private Long id;
  private String oaNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private String customer;
  private LocalDate applyDate;
  private Integer productCount;
  private String ingestStatus;
  private String classificationStatus;
  private String bomAggregateStatus;
  private String calcStatus;
  private Boolean calculable;
}
