package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_monthly_price")
public class FactorMonthlyPrice {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal price;
  private Integer taxIncluded;
  private Long sourceUploadBatchId;
  private Long latestAdjustBatchId;
  private String latestAdjustSourceType;
  private String latestAdjustedBy;
  private LocalDateTime latestAdjustedAt;
  private String sourceTag;
  private String status;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
}
