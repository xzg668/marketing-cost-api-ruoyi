package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lp_quote_bom_confirmation")
public class QuoteBomConfirmation {
  public static final String STATUS_CONFIRMED = "CONFIRMED";
  public static final String STATUS_INVALID = "INVALID";
  public static final String STATUS_STALE = "STALE";

  @TableId(type = IdType.AUTO)
  private Long id;

  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String confirmStatus;
  private Integer confirmVersion;
  private Integer rowCount;
  private Integer manualModifiedCount;
  private Integer replaceCount;
  private Integer usageAdjustCount;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmRemark;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
