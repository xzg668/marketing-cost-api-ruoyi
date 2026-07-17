package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuotePricePrepareGenerateRequest {
  private String periodMonth;
  private LocalDateTime priceAsOfTime;
  private String priceTypeConfirmNo;
  private QuotePriceScenarioType scenarioType;
  private String scenarioGroupNo;
  private String sourcePrepareNo;
  private Map<String, BigDecimal> variableOverrides;
  /**
   * 是否在 OA 锁价快照成功后同步生成财务基准快照。
   *
   * <p>为空时按 {@code true} 处理，价格源预检查会显式传 {@code false}。
   */
  private Boolean includeFinanceComparison;
}
