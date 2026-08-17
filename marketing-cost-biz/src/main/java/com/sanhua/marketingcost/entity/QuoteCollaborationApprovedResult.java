package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_approved_result")
public class QuoteCollaborationApprovedResult {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String resultNo;
  private String resultType;
  private Long sourceProductTaskId;
  private Long sourceReviewId;
  private String productCode;
  private String productForm;
  private String applicableOrgCode;
  private String sourceObjectType;
  private Long sourceObjectId;
  private String sourceSystem;
  private String sourceVersionText;
  private String structureFingerprint;
  private String u9ContextFingerprint;
  private String validityPolicyCode;
  private Integer validityMonths;
  private LocalDateTime validFrom;
  private LocalDateTime validUntil;
  private String resultStatus;
  private String invalidReason;
  private LocalDateTime invalidatedAt;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
