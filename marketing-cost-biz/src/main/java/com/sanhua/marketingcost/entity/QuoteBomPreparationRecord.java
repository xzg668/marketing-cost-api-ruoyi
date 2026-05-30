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
@TableName("lp_quote_bom_preparation_record")
public class QuoteBomPreparationRecord {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long quoteBomStatusId;
  private Long oaFormId;
  private Long oaFormItemId;
  private String oaNo;
  private String quoteProductCode;
  private String productType;
  private String bareProductCode;
  private Integer needPackage;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private String costPeriodMonth;
  private String preparationStatus;
  private String reviewStatus;
  private Long technicianUserId;
  private String technicianName;
  private Long taskId;
  private Long reviewerUserId;
  private String reviewerName;
  private LocalDateTime reviewedAt;
  private String costingBuildBatchId;
  private Long reusedFromTaskId;
  private String reusedFromOaNo;
  private Long reusedFromOaFormItemId;
  private String reuseType;
  private LocalDate reuseValidUntil;
  private Integer activeFlag;
  private String errorMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
