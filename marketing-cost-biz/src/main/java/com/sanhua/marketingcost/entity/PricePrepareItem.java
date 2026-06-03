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
  private String topProductCode;
  private Long bomRowId;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private String status;
  private String resultRefType;
  private Long resultRefId;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
