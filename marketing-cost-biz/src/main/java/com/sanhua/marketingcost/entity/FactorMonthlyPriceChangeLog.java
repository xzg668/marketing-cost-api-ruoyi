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
@TableName("lp_factor_monthly_price_change_log")
public class FactorMonthlyPriceChangeLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorMonthlyPriceId;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal oldPrice;
  private BigDecimal newPrice;
  private String changeType;
  private Long sourceUploadBatchId;
  private Long adjustBatchId;
  private String sourceType;
  private String changedBy;
  private String remark;
  private LocalDateTime createdAt;
}
