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
  /** PRC-01：原稿/财务版本契约；旧数据的未知来源保持为空。 */
  private String priceOrgCode;
  private String schemeCode = "LEGACY";
  private String conditionCode;
  private Integer revisionNo;
  private String unit;
  private LocalDateTime publishedAt;

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal price;
  private Integer taxIncluded;
  private Long sourceUploadBatchId;
  private String sourceTag;
  private String status;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
}
