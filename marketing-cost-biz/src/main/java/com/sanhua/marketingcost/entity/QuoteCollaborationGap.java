package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_gap")
public class QuoteCollaborationGap {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String gapNo;
  private Long productTaskId;
  private String gapCategory;
  private String gapType;
  private String sourceType;
  private Long sourceId;
  private String gapFingerprint;
  private String bomNodeKey;
  private String bomPath;
  private BigDecimal bomQuantity;
  private String bomUnit;
  private String accountingMonth;
  private String applicableOrgCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String materialRole;
  private String suggestedPriceType;
  private String reasonCode;
  private String reasonMessage;
  private String gapStatus;
  private Long currentPriceDraftId;
  private LocalDateTime resolvedAt;
  private Long resolvedBy;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
