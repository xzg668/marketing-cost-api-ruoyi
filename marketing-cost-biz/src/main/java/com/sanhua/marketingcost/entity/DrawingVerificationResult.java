package com.sanhua.marketingcost.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Mapper 只提供插入和读取；数据库拒绝修改/删除已保存证据。 */
@Getter
@Setter
public class DrawingVerificationResult {
  private Long id;
  private Long verificationTaskId;
  private String eventType;
  private Integer upstreamRevision;
  private String rawSummaryJson;
  private String dataJson;
  private String plmDataJson;
  private Long plmSourceResultId;
  private String contentFingerprint;
  private Long confirmedAtMs;
  private LocalDateTime receivedAt;
}
