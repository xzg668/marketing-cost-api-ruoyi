package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 价格准备明细表。 */
@Getter
@Setter
@TableName("lp_price_prepare_item")
public class PricePrepareItem {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String prepareNo;
  private String periodMonth;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private Long bomRowId;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private String priceType;
  private String status;
  private String resultRefType;
  private Long resultRefId;
  private Long sourcePriceRecordId;
  private String sourcePriceBatchNo;
  private String supplierName;
  private String supplierCode;
  private BigDecimal supplyRatio;
  private Long supplyRatioRecordId;
  private LocalDate sourceEffectiveFrom;
  private LocalDate sourceEffectiveTo;
  private Integer carriedForward;
  private String warningMessage;
  private String message;
  /** 1=当前最新准备快照；0=历史快照。历史核算仍可按 prepare_no 读取。 */
  private Integer currentFlag;
  private String settlementKey;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
