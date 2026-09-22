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
@TableName("lp_quote_tech_task")
public class QuoteTechTask {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String taskNo;
  private Long oaFormId;
  private Long oaFormItemId;
  private Long oaFlowId;
  private String oaEnvironment;
  private Integer oaAssignmentVersion;
  private Long oaDispatchMessageId;
  private String oaNo;
  private String accountingMonth;
  private String businessUnitType;
  private String applicableOrgCode;
  private Long assigneeUserId;
  private String assigneeName;
  private String taskStatus;
  private Integer taskVersion;
  private Integer reviewRound;
  private String reviewStatus;
  private String submissionIdempotencyKey;
  private String submissionFingerprint;
  private String sourceSystem;
  private String sourceRequestId;
  private String externalSystem;
  private String externalTaskId;
  private String externalTaskStatus;
  private Long externalCallbackSeq;
  private String externalLastEventId;
  private LocalDateTime externalLastSyncAt;
  private Integer externalRetryCount;
  private LocalDateTime externalNextRetryAt;
  private String externalLastError;
  private Long proxyOperatorUserId;
  private String proxyOperatorName;
  private String proxyReason;
  private String proxyRequestId;
  private LocalDateTime proxyStartedAt;
  private LocalDateTime dueAt;
  private LocalDateTime submittedAt;
  private LocalDateTime approvedAt;
  private LocalDateTime cancelledAt;
  private Integer activeFlag;
  private String activeLockKey;
  private Long createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  private Long updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
