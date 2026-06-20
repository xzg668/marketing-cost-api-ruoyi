package com.sanhua.marketingcost.dto.quotecosting;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuotePriceTypeConfirmationSummaryResponse {
  private Long id;
  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String bomConfirmNo;
  private String status;
  private Integer totalCount;
  private Integer confirmedCount;
  private Integer gapCount;
  private Integer referencePriceCount;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String message;
}
