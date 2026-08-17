package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_integration_outbox")
public class IntegrationOutbox {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String eventId;
  private String idempotencyKey;
  private String destination;
  private String aggregateType;
  private Long aggregateId;
  private Integer aggregateVersion;
  private String eventType;
  private String eventVersion;
  private String payloadJson;
  private String payloadHash;
  private String sendPolicy;
  private String sendStatus;
  private Integer retryCount;
  private LocalDateTime nextRetryAt;
  private Integer lastHttpStatus;
  private String responseJson;
  private String lastErrorMessage;
  private LocalDateTime occurredAt;
  private LocalDateTime sentAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
