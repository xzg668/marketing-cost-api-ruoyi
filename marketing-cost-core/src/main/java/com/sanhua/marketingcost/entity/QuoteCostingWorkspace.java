package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_costing_workspace")
public class QuoteCostingWorkspace {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String businessUnitType;
  private String workspaceStatus;
  private String currentStep;
  private String inputFingerprint;
  private String sourceRevision;
  private String lastSuccessInputFingerprint;
  private String lastSuccessSourceRevision;
  private String bomSourceFingerprint;
  private String bomRuleFingerprint;
  private String currentBomBuildBatchId;
  private String currentPrepareNo;
  private Long currentCostVersionId;
  private Integer gapCount;
  private Integer carriedForwardPriceCount;
  private String dataQualityStatus;
  private Integer dataQualityWarningCount;
  private String dataQualitySummary;
  private String staleReasonCode;
  private String lastErrorStep;
  private String lastErrorCode;
  private String lastErrorMessage;
  private Long lastTaskId;
  private Integer lockVersion;
  private LocalDateTime lastCheckedAt;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
