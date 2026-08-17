package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_price_draft")
public class QuotePriceDraft {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String draftNo;
  private Long productTaskId;
  private Long gapId;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String businessUnitType;
  private String orgCode;
  private String priceType;
  private String sourceMode;
  private String referenceSourceType;
  private Long referenceSourceId;
  private String referenceVersionText;
  private String targetSourceType;
  private String supplierCode;
  private String supplierName;
  private String unit;
  private Integer taxIncluded;
  private BigDecimal taxRate;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String draftStatus;
  private String validationStatus;
  private String validationMessage;
  private Integer draftVersion;
  private String draftFingerprint;
  private LocalDateTime submittedAt;
  private String publishedSourceTable;
  private Long publishedSourceId;
  private String publishBatchNo;
  private LocalDateTime publishedAt;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
