package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 月度调价成本核算结果表。 */
@Getter
@Setter
@TableName("lp_monthly_reprice_result")
public class MonthlyRepriceResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String repriceNo;
  private String pricingMonth;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String calcObjectKey;
  private BigDecimal totalCost;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal auxiliaryCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private BigDecimal salesCost;
  private BigDecimal financeCost;
  private String costEngineVersion;
  private String priceVersion;
  private String ruleVersion;
  private Long sourceCostResultId;
  private String calcStatus;
  private String calcMessage;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}

