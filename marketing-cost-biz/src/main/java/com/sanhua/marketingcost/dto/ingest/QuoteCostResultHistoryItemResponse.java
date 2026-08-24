package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 报价产品的只读成本结果索引。原报价核算与月度调价共用这一套展示字段。 */
@Data
public class QuoteCostResultHistoryItemResponse {
  private String resultType;
  private String resultTypeLabel;
  private Long sourceId;
  private Long versionId;
  private String repriceNo;
  private String resultNo;
  private String periodMonth;
  private String status;
  private BigDecimal totalCost;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal auxiliaryCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private BigDecimal salesCost;
  private BigDecimal financeCost;
  private LocalDateTime completedAt;
  private boolean defaultResult;
  private boolean fullCostSheetAvailable;
}
