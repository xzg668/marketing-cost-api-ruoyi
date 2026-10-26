package com.sanhua.marketingcost.entity.monthly;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Versioned source row; joined batch context is read-only. */
@Getter
@Setter
public class HouseholdInquiryBatchRow {
  private Long id;
  private String businessUnitType;
  private String previewId;
  private String publishRequestId;
  private String previewFingerprint;
  private String fileKey;
  private String fileSha256;
  private String fileName;
  private String worksheetName;
  private String parserSchema;
  private String sourceMonth;
  private String sourceMonthOrigin;
  private String pricingUnit;
  private String currency;
  private String taxBasis;
  private String previewJson;
  private String status;
  private LocalDateTime expiresAt;
  private String resultJson;
  private LocalDateTime publishedAt;
  private String createdBy;
  private LocalDateTime createdAt;
}
