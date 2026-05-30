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

/**
 * 制造件价格生成明细。
 *
 * <p>一行代表同一生成批次内 parent_material_no + child_material_no + scrap_code 的一条核算明细。
 * 重量字段统一为 g；后续计算遇到元/kg 单价时必须先除以 1000。
 */
@Getter
@Setter
@TableName("lp_make_part_price_calc_row")
public class MakePartPriceCalcRow {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String calcBatchId;
  private String oaNo;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String parentMaterialNo;
  private String parentMaterialName;
  private String drawingNo;
  private String itemProcessType;
  private String childMaterialNo;
  private String childMaterialName;
  private String childMaterialSpec;
  private String stockUnit;
  private BigDecimal qtyPerParent;
  private BigDecimal grossWeightG;
  private BigDecimal netWeightG;
  private String rawPriceType;
  private BigDecimal rawUnitPrice;
  private String scrapCode;
  private String scrapName;
  private String scrapPriceType;
  private BigDecimal scrapUnitPrice;
  private BigDecimal outsourceFee;
  private BigDecimal costPrice;
  private BigDecimal parentTotalCostPrice;
  private Boolean priceComplete;
  private String status;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
