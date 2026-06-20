package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuoteCostRunWorkbenchResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private QuoteCostRunSummaryResponse latestTrial;
  private QuoteCostRunSummaryResponse latestConfirmed;
  private QuoteCostRunSummaryResponse currentDisplayVersion;
  private List<CostRunVersionItemResponse> versions = new ArrayList<>();
  private CostRunResultDto resultHeader;
  private List<CostRunPartItemDto> partItems = new ArrayList<>();
  private List<CostRunCostItemDto> costItems = new ArrayList<>();
  private boolean canStartTrial;
  private boolean canConfirm;
  private List<String> blockingReasons = new ArrayList<>();

  @Data
  public static class CostRunVersionItemResponse {
    private Long id;
    private String costRunNo;
    private String versionNo;
    private String displayVersionNo;
    private String status;
    private String displayStatus;
    private BigDecimal totalCost;
    private Integer partItemCount;
    private Integer costItemCount;
    private LocalDateTime trialFinishedAt;
    private LocalDateTime confirmedAt;
    private String confirmedBy;
    private boolean canConfirm;
    private boolean canViewSheet;
    private boolean canViewTrace;
    private boolean stale;
    private boolean currentConfirmed;
  }
}
