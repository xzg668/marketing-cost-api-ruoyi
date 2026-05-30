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

/** 月度调价成本项明细表。 */
@Getter
@Setter
@TableName("lp_monthly_reprice_cost_item")
public class MonthlyRepriceCostItem {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String repriceNo;
  private String pricingMonth;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String oaNo;
  private String calcObjectKey;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private Integer lineNo;
  private String costItemCode;
  private String costItemName;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  private String calcFormula;
  private String calcStatus;
  private String calcMessage;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}

