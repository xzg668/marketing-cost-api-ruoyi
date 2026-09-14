package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_tech_data_version")
public class QuoteTechDataVersion {
  public static final String STATUS_DRAFT = "DRAFT";
  public static final String STATUS_SUBMITTED = "SUBMITTED";
  public static final String STATUS_RETURNED = "RETURNED";
  public static final String STATUS_APPROVED = "APPROVED";
  public static final String STATUS_VOIDED = "VOIDED";

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long productId;
  private Integer versionNo;
  private String versionStatus;
  private String productModel;
  private String productProperty;
  private Integer newProductFlag;
  private BigDecimal packageTotalAmount;
  private BigDecimal auxiliaryTotalAmount;
  private BigDecimal salaryTotalAmount;
  private String contentFingerprint;
  private String referenceSnapshotJson;
  private Long createdFromVersionId;
  private Long submittedBy;
  private LocalDateTime submittedAt;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer rowVersion;
  private Long createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  private Long updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
