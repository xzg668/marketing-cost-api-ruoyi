package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一个产品中一位办理人的不可变发送快照；PREPARED 不表示 OA 已接收或批准。 */
@Getter
@Setter
@TableName("lp_quote_tech_submission")
public class QuoteTechSubmission {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long taskId;
  private Long recipientId;
  private String moduleTypesJson;
  private String leaderExternalId;
  private Long productId;
  private Long technicalVersionId;
  private Integer submissionRound;
  private String requestId;
  private Integer expectedTaskVersion;
  private Integer expectedProductVersion;
  private Integer contentSchemaVersion;
  private String contentFingerprint;
  private String contentSnapshotJson;
  private String summaryJson;
  private Long previousSubmissionId;
  private Long assigneeUserId;
  private Long submittedBy;
  private LocalDateTime preparedAt;
  private String submissionStatus;
  private String externalFlowId;
  private String externalSubmissionId;
  private Integer rowVersion;
  private Long outboundMessageId;
  private LocalDateTime sentAt;
  private Long decisionMessageId;
  private LocalDateTime decidedAt;
}
