package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_tech_module")
public class QuoteTechModule {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long productId;
  private String moduleType;
  private Integer requiredFlag;
  private String requirementReasonCode;
  private String requirementReason;
  private String entryMode;
  private String moduleStatus;
  private Long currentVersionId;
  private String referenceSourceType;
  private String referenceSourceId;
  private String referenceSourceVersion;
  private String referenceFingerprint;
  private String referenceSnapshotJson;
  private LocalDateTime referencedAt;
  private String lastValidationCode;
  private String lastValidationMessage;
  private Integer rowVersion;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
