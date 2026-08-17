package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_product_task")
public class QuoteCollaborationProductTask {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String productTaskNo;
  private Long originCollaborationId;
  private String accountingMonth;
  private String businessUnitType;
  private String applicableOrgCode;
  private String materialOrgCode;
  private String priceOrgCode;
  private String productCode;
  private String temporaryProductKey;
  private String productName;
  private String productSpec;
  private String productModel;
  private String productForm;
  private String primaryScope;
  private Integer needBom;
  private Integer needPackage;
  private Integer needPrice;
  private Integer openGapCount;
  private String taskStatus;
  private Long originalTechnicianUserId;
  private String originalTechnicianName;
  private Long currentAssigneeUserId;
  private String currentAssigneeName;
  private Integer taskVersion;
  private String activeLockKey;
  private Integer activeFlag;
  private Long preparationId;
  private Long supplementVersionId;
  private Long packageReferenceId;
  private String electronicBomFingerprint;
  private String lastValidationStatus;
  private LocalDateTime lastValidationAt;
  private LocalDateTime techSubmittedAt;
  private LocalDateTime readyAt;
  private String cancelReason;
  private Long createdBy;
  private String createdByName;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private String updatedByName;
  private LocalDateTime updatedAt;
}
