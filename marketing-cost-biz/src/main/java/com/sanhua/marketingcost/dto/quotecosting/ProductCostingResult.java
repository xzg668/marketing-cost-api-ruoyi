package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

/** 产品级统一核算流水线结果；业务缺口作为结构化结果返回，不伪装成系统异常。 */
@Data
public class ProductCostingResult {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String pipelineStatus;
  private String blockingStatus;
  private String currentStep;
  private String errorCode;
  private String message;
  private Integer gapCount;
  private Integer warningCount;
  private Long collaborationTaskId;
  private String collaborationStatus;
  private String collaborationAssigneeName;
  private String collaborationMessage;
  private String pricePrepareNo;
  private Long costVersionId;
  private String costRunNo;
  private String versionNo;
  private BigDecimal totalCost;
  private boolean reusedSuccess;
  private boolean retryable;
}
