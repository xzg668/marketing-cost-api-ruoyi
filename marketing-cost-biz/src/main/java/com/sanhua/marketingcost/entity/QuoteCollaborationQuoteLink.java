package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_quote_link")
public class QuoteCollaborationQuoteLink {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long productTaskId;
  private Long collaborationId;
  private Long oaFormId;
  private Long oaFormItemId;
  private String oaNo;
  private String productCode;
  private String accountingMonth;
  private String applicableOrgCode;
  private String linkType;
  private Long approvedResultId;
  private String linkStatus;
  private String latestPricePrepareNo;
  private Integer repriceGapCount;
  private LocalDateTime readyAt;
  private Integer activeFlag;
  private String activeLinkKey;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
