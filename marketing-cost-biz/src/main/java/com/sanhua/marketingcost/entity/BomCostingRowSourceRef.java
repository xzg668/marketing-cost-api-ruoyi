package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_bom_costing_row_source_ref")
public class BomCostingRowSourceRef {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long costingRowId;
  private String oaNo;
  private Long oaFormItemId;
  private String quoteProductCode;
  private String sourcePartType;
  private Long sourceRawHierarchyId;
  private Long sourceTaskId;
  private Long preparationId;
  private Long supplementVersionId;
  private Long supplementDetailId;
  private Long packageReferenceId;
  private Long packageReferenceDetailId;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private Long sourceSnapshotId;
  private Long sourceSnapshotDetailId;
  private Long sourceU9BomId;
  private String sourcePath;
  private LocalDateTime createdAt;
}
