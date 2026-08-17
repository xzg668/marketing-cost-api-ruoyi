package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_task")
public class QuoteCollaborationTask {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String collaborationNo;
  private Long oaFormId;
  private String oaNo;
  private Integer roundNo;
  private String businessUnitType;
  private String accountingMonth;
  private String sourceSystem;
  private String masterStatus;
  private Long financeReviewerUserId;
  private String financeReviewerName;
  private Long currentReviewId;
  private Integer ownedProductCount;
  private Integer techSubmittedCount;
  private Integer returnedProductCount;
  private Integer readyProductCount;
  private Integer taskVersion;
  private String oaProcessInstanceId;
  private String lastErrorCode;
  private String lastErrorMessage;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
