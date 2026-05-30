package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_bom_supplement_version")
public class QuoteBomSupplementVersion {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long preparationId;
  private Long taskId;
  private String taskNo;
  private String oaNo;
  private Long oaFormItemId;
  private String quoteProductCode;
  private String productType;
  private String supplementScope;
  private String bomSource;
  private Integer versionNo;
  private String versionStatus;
  private Integer activeFlag;
  private String periodMonth;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private LocalDate reuseValidUntil;
  private Long reusedFromVersionId;
  private Long submittedBy;
  private String submittedByName;
  private LocalDateTime submittedAt;
  private Long reviewerUserId;
  private String reviewerName;
  private LocalDateTime reviewedAt;
  private String reviewComment;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
