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
@TableName("lp_factor_adjust_price")
public class FactorAdjustPrice {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long adjustBatchId;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private String unit;
  private BigDecimal originalPrice;
  private BigDecimal adjustedPrice;
  private BigDecimal priceDelta;
  private BigDecimal changeRate;
  private String matchMethod;
  /** 1 表示本条调价价也写入 lp_factor_monthly_price，成为日常报价生效价。 */
  private Integer applyToDaily;
  private String status;
  private String failReason;
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private Integer deleted;
}
