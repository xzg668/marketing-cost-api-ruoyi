package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 一个确定原稿/财务输入组合的公共价格；供货比率只参与后续选供。 */
@Data
@TableName("lp_finance_effective_price")
public class FinanceEffectivePrice {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String businessUnitType;
  private String orgCode;
  private String priceType;
  private Long originalId;
  private Integer originalRevision;
  private String originalMonth;
  private String ruleIdentityKey;
  private String ruleFingerprint;
  private String materialCode;
  private String supplierCode;
  private String supplierName;
  private String specification;
  private String unit;
  private Long financeBatchId;
  private String financeMonth;
  private String schemeCode;
  private String conditionCode;
  private String effectiveFromMonth;
  private String calculatorVersion;
  private String inputFingerprint;
  private BigDecimal unitPrice;
  private String calculationJson;
  private LocalDateTime createdAt;
}
