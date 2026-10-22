package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_drawing_verification_task")
public class DrawingVerificationTask {
  @TableId(type = IdType.AUTO) private Long id;
  private String sourceSystem;
  private String requestId;
  private String externalTaskId;
  private String oaNo;
  private Long oaFormItemId;
  private String businessUnitType;
  private String materialOrgCode;
  private String priceOrgCode;
  private String accountingMonth;
  private String drawingNo;
  private String inputSnapshotJson;
  private String activeScopeKey;
  private String taskState;
  private String editUrl;
  private Integer differenceCount;
  private Long currentResultId;
  private Long consumedResultId;
  private String consumptionState;
  private String errorCode;
  private String errorMessage;
  private Integer rowVersion;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
