package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 价格准备批次表。 */
@Getter
@Setter
@TableName("lp_price_prepare_batch")
public class PricePrepareBatch {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String prepareNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String priceTypeConfirmNo;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer warningCount;
  private Integer gapCount;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
