package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGenerateRequest {
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private java.util.List<String> topProductCodes;
  private String periodMonth;
  private LocalDateTime priceAsOfTime;
  private String businessUnitType;
  private String bomPurpose;
  private String sourceType;
  private QuotePriceScenarioType scenarioType;
  private String scenarioGroupNo;
  private String sourcePrepareNo;
  private Map<String, BigDecimal> variableOverrides;
}
