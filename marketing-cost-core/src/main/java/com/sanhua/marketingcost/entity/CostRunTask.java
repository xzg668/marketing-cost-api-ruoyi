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
@TableName("lp_cost_run_task")
public class CostRunTask {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String batchNo;
  private String scene;
  private String sourceNo;
  private String calcObjectKey;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String businessUnitType;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private Long adjustBatchId;
  private String bomSourcePolicy;
  private String status;
  private Integer progress;
  private String workerId;
  private LocalDateTime lockedAt;
  private LocalDateTime lockExpireTime;
  private Integer retryCount;
  private Integer maxRetryCount;
  private String requestSnapshotJson;
  private String resultSummaryJson;
  private String errorMessage;
  private String errorStack;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
