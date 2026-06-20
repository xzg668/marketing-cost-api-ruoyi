package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("lp_quote_bom_confirmation_log")
public class QuoteBomConfirmationLog {
  public static final String ACTION_CONFIRM = "CONFIRM";
  public static final String ACTION_CANCEL = "CANCEL";
  public static final String ACTION_STALE = "STALE";

  @TableId(type = IdType.AUTO)
  private Long id;

  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String actionType;
  private String beforeStatus;
  private String afterStatus;
  private String operatorId;
  private LocalDateTime operatedAt;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
