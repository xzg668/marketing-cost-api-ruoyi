package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lp_quote_price_type_confirm_batch")
public class QuotePriceTypeConfirmBatch {
  public static final String STATUS_CONFIRMED = "CONFIRMED";
  public static final String STATUS_INVALID = "INVALID";
  public static final String STATUS_STALE = "STALE";

  @TableId(type = IdType.AUTO)
  private Long id;

  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String bomConfirmNo;
  private String status;
  private Integer totalCount;
  private Integer confirmedCount;
  private Integer gapCount;
  private Integer referencePriceCount;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
