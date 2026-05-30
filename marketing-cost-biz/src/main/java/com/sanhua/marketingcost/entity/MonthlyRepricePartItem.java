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

/** 月度调价部品明细表。 */
@Getter
@Setter
@TableName("lp_monthly_reprice_part_item")
public class MonthlyRepricePartItem {

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
  private String partCode;
  private String partName;
  private String partDrawingNo;
  private String material;
  private String shapeAttr;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private Long priceSourceId;
  private Long linkedCalcItemId;
  private String calcStatus;
  private String calcMessage;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}

