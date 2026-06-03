package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 制造件子项人工确认无废料记录。 */
@Getter
@Setter
@TableName("lp_make_part_no_scrap_confirmation")
public class MakePartNoScrapConfirmation {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REVOKED = "REVOKED";

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String materialNo;
  private String materialName;
  private String effectiveFromMonth;
  private String effectiveToMonth;
  private String status;
  private String confirmReason;
  private String sourceOaNo;
  private Long sourceGapId;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String revokedBy;
  private LocalDateTime revokedAt;
  private String revokeReason;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
