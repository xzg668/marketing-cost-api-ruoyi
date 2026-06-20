package com.sanhua.marketingcost.dto.quotecosting;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuotePricePrepareGenerateRequest {
  private String periodMonth;
  private LocalDateTime priceAsOfTime;
  private String priceTypeConfirmNo;
}
