package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_external_task")
public class QuoteCollaborationExternalTask {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String externalSystem;
  private Long collaborationId;
  private Long productTaskId;
  private String taskKind;
  private Integer logicalTaskVersion;
  private String externalProcessId;
  private String externalTaskId;
  private String externalStatus;
  private String assigneeUserId;
  private String assigneeName;
  private String entryUrl;
  private Integer currentFlag;
  private LocalDateTime openedAt;
  private LocalDateTime completedAt;
  private LocalDateTime lastSyncAt;
  private String lastErrorMessage;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
