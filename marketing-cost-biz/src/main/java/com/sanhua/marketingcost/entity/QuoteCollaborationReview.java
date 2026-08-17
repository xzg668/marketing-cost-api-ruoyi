package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_review")
public class QuoteCollaborationReview {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String reviewNo;
  private Long collaborationId;
  private Integer reviewRound;
  private String reviewStatus;
  private Long reviewerUserId;
  private String reviewerName;
  private Integer sourceTaskVersion;
  private Integer productCount;
  private Integer priceDraftCount;
  private Integer passedItemCount;
  private Integer rejectedItemCount;
  private String reviewComment;
  private String publishBatchNo;
  private LocalDateTime submittedAt;
  private LocalDateTime reviewedAt;
  private LocalDateTime effectiveAt;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
