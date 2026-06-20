package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lp_quote_price_type_confirm_item")
public class QuotePriceTypeConfirmItem {
  public static final String STATUS_CONFIRMED = "CONFIRMED";
  public static final String STATUS_MISSING_TYPE = "MISSING_TYPE";
  public static final String STATUS_CHILD_MISSING_TYPE = "CHILD_MISSING_TYPE";

  @TableId(type = IdType.AUTO)
  private Long id;

  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private Long bomRowId;
  private String parentMaterialCode;
  private String materialCode;
  private String materialName;
  private String objectType;
  private BigDecimal quantity;
  private String priceType;
  private String priceTypeSource;
  private LocalDate typeEffectiveFrom;
  private LocalDate typeEffectiveTo;
  private String status;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
