package com.sanhua.marketingcost.dto.quotecosting;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteBomConfirmationSummaryResponse {
  private Long id;
  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String confirmStatus;
  private Integer confirmVersion;
  private Integer rowCount;
  private Integer manualModifiedCount;
  private Integer replaceCount;
  private Integer usageAdjustCount;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmRemark;
}
