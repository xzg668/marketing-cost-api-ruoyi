package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_integration_inbox")
public class IntegrationInbox {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String sourceSystem;
  private String callbackId;
  private String idempotencyKey;
  private String callbackType;
  private String payloadJson;
  private String payloadHash;
  private String signatureStatus;
  private String processStatus;
  private String processMessage;
  private LocalDateTime receivedAt;
  private LocalDateTime processedAt;
  private LocalDateTime createdAt;
}
