package com.sanhua.marketingcost.dto.quotecosting;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuotePricePrepareSummaryResponse {
  private Long id;
  private String prepareNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String priceTypeConfirmNo;
  private String periodMonth;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer warningCount;
  private Integer gapCount;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private String message;
}
