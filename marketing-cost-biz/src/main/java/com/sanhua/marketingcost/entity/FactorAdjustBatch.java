package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_adjust_batch")
public class FactorAdjustBatch {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String adjustBatchNo;
  /** 调整类型：NORMAL 普通维护；MONTHLY 月度调价。 */
  private String adjustType;
  private String pricingMonth;
  private String businessUnitType;
  /** 月度调价用途：只重算历史报价，或同时同步为日常报价生效价。 */
  private String usageScope;
  private String sourceType;
  private String sourceFileName;
  private String fileSha256;
  private String contentHash;
  private Integer totalCount;
  private Integer changedCount;
  private Integer noChangeCount;
  private Integer skippedCount;
  private Integer failedCount;
  private String status;
  private String uploadedBy;
  private LocalDateTime uploadedAt;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private Integer deleted;
}
