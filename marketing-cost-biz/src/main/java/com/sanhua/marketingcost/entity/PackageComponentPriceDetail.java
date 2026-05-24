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

/** 包装组件月度价格明细。 */
@Getter
@Setter
@TableName("lp_package_component_price_detail")
public class PackageComponentPriceDetail {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long priceId;
  private Long snapshotDetailId;
  private String packageMaterialCode;
  private String periodMonth;
  private Integer lineNo;
  private String childMaterialCode;
  private String childMaterialName;
  private String childMaterialSpec;
  private BigDecimal qtyPerParent;
  private BigDecimal childParentBaseQty;
  private String priceType;
  private String sourcePriceTypeText;
  private BigDecimal childUnitPrice;
  private BigDecimal childAmount;
  private String priceSource;
  private Long priceSourceId;
  private String priceStatus;
  private String missingReason;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
