package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 每次报价员确认独立留痕；不能覆盖之前的确认。 */
@Getter
@Setter
@TableName("lp_supplier_price_decision")
public class SupplierPriceDecision {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String scopeKey;
  private String fingerprint;
  private String businessUnitType;
  private String oaNo;
  private Long oaFormItemId;
  private String periodMonth;
  private LocalDate pricingDate;
  private String orgCode;
  private String materialCode;
  private String priceType;
  private String decision;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String retryStatus;
  private String retryMessage;
}
