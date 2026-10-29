package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 月度调价批次表。 */
@Getter
@Setter
@TableName("lp_monthly_reprice_batch")
public class MonthlyRepriceBatch {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String repriceNo;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String bomSourcePolicy;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String executionBackend;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer failedCount;
  private Integer skippedCount;
  private String costEngineVersion;
  private String priceVersion;
  private String ruleVersion;
  private String createdBy;
  private String createdName;
  private String confirmedBy;
  private String confirmedName;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime confirmedAt;
  private String remark;
  /** Null identifies preserved legacy batches; 1 is the 1130 contract. */
  private Integer contractVersion;
  private String goLiveMonth;
  private Long resultRevision;
  private Long checkedRevision;
  private String checkStatus;
  private String checkResultJson;
  private LocalDateTime checkedAt;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
