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

/** 包装组件月度结构快照明细。 */
@Getter
@Setter
@TableName("lp_package_component_snapshot_detail")
public class PackageComponentSnapshotDetail {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long snapshotId;
  private String packageMaterialCode;
  private String periodMonth;
  private Integer lineNo;
  private String childMaterialCode;
  private String childMaterialName;
  private String childMaterialSpec;
  private String childShapeAttr;
  private BigDecimal qtyPerParent;
  private BigDecimal qtyPerTop;
  private BigDecimal childParentBaseQty;
  private Long sourceHierarchyId;
  private String sourceParentCode;
  private String sourcePath;
  private Integer sourceSortSeq;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
