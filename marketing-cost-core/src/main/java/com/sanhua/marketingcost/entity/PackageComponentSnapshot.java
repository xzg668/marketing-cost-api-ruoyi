package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 包装组件月度结构快照主表。
 *
 * <p>同一 packageMaterialCode + periodMonth 在一个月内只锁定一份结构。
 */
@Getter
@Setter
@TableName("lp_package_component_snapshot")
public class PackageComponentSnapshot {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String packageMaterialCode;
  private String packageMaterialName;
  private String periodMonth;
  private String status;
  private String sourceType;
  private String sourceQuoteNo;
  private String sourceOaNo;
  private String sourceTopProductCode;
  private String sourceBomPurpose;
  private String sourceBomSourceType;
  private LocalDate sourceAsOfDate;
  private Long sourceRawHierarchyId;
  private String sourcePath;
  private BigDecimal packageQtyPerParent;
  private BigDecimal packageQtyPerTop;
  private BigDecimal packageParentBaseQty;
  private String referencePackageCode;
  private String missingReason;
  private LocalDateTime lockedAt;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
