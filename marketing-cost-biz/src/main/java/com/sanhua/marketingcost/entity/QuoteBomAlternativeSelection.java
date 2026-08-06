package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 报价 BOM 标准件/替代件选择及版本历史。
 *
 * <p>选择、版本切换和并发规则由 QBA-05 选择服务统一维护。
 */
@Data
@TableName("lp_quote_bom_alternative_selection")
public class QuoteBomAlternativeSelection {

  public static final String CHILD_TYPE_STANDARD = "STANDARD";
  public static final String CHILD_TYPE_ALTERNATIVE = "ALTERNATIVE";

  public static final String SOURCE_AUTO_STANDARD = "AUTO_STANDARD";
  public static final String SOURCE_MANUAL_STANDARD = "MANUAL_STANDARD";
  public static final String SOURCE_MANUAL_ALTERNATIVE = "MANUAL_ALTERNATIVE";
  public static final String SOURCE_INHERITED_MONTHLY = "INHERITED_MONTHLY";

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_SUPERSEDED = "SUPERSEDED";
  public static final String STATUS_STALE = "STALE";

  public static final Integer CURRENT_SLOT = 1;

  @TableId(type = IdType.AUTO)
  private Long id;

  private String selectionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String priceOrgCode;

  private String alternativeGroupKey;
  private String parentPath;
  private String parentMaterialCode;
  private String parentMaterialName;
  private Integer childSeq;
  private String processSeq;
  private String bomPurpose;
  private String bomVersion;
  private LocalDate sourceEffectiveFrom;
  private LocalDate sourceEffectiveTo;

  private String standardMaterialCode;
  private String selectedMaterialCode;
  private String selectedChildType;
  private String selectionSource;
  private Integer selectionVersion;
  private String selectionStatus;
  private Integer currentSlot;

  @TableField("candidate_snapshot_json")
  private String candidateSnapshotJson;

  private String sourceImportBatchId;
  private String sourceBuildBatchId;
  private String selectedBy;
  private LocalDateTime selectedAt;
  private String selectionRemark;
  private Long inheritedMonthlySnapshotId;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
