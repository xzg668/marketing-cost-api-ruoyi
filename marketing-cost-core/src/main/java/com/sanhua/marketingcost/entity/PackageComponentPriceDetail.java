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
  /** 本次计算底稿随报价价格准备冻结，不写回可复用的包装月价明细。 */
  @TableField(exist = false)
  private String inputSnapshotJson;
  private Long priceSourceId;
  private String priceStatus;
  private String missingReason;
  /** 当前计算返回的结构化失败原因，供报价缺口归类；不写入可复用月价明细。 */
  @TableField(exist = false)
  private String failureCode;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
