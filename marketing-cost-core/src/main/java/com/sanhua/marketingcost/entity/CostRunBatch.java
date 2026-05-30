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
@TableName("lp_cost_run_batch")
public class CostRunBatch {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String batchNo;
  private String scene;
  private String sourceNo;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String businessUnitType;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer failedCount;
  private Integer skippedCount;
  private Integer progress;
  private String requestSnapshotJson;
  private String resultSummaryJson;
  private String errorMessage;
  private String errorStack;
  private String createdBy;
  private String createdName;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
