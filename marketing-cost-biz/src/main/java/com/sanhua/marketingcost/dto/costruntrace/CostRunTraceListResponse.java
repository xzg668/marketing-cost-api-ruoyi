package com.sanhua.marketingcost.dto.costruntrace;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostRunTraceListResponse {
  private Long costRunVersionId;
  private String costRunNo;
  private String versionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String pricingMonth;
  private long total;
  private List<CostRunTraceListItemDto> records = new ArrayList<>();
}
