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
@TableName("lp_quote_tech_product")
public class QuoteTechProduct {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;
  private Integer contentSchemaVersion;
  private Long oaFormItemId;
  private Integer levelNo;
  private String materialNo;
  private String productName;
  private String sourceModel;
  private String sourceSpec;
  private String quoteNo;
  private String accountingMonth;
  private String sourceSnapshotJson;
  private String sourceFingerprint;
  private String productStatus;
  private Long currentEditVersionId;
  private Long latestSubmittedVersionId;
  private Long effectiveVersionId;
  private Integer effectiveReviewRound;
  private LocalDateTime effectiveAt;
  private Integer activeFlag;
  private String activeLockKey;
  private Integer rowVersion;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
