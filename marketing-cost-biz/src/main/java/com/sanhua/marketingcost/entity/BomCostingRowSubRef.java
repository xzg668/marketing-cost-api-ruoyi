package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * T8 新增：BOM 父件结算行 → 命中子件清单固化表。
 *
 * <p>写入时机：当 {@code drill_action=ROLLUP_TO_PARENT} 规则命中某个子件时，
 * Flatten 把该节点的父作为结算行写入 {@code lp_bom_costing_row}，同时为每个
 * 命中的子件写一条本表记录（通过 {@link #costingRowId} 关联回父行）。
 *
 * <p>读取时机：T9 取价阶段 {@code SubtreeCompositeResolver} 直接查
 * {@code WHERE costing_row_id = ?} 拿命中子件清单，不再跑 matcher。
 *
 * <p>版本冻结语义：FK 级联删除 + 和 costing_row 一起 append-only 写。
 */
@TableName("lp_bom_costing_row_sub_ref")
public class BomCostingRowSubRef {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 指向父件的 lp_bom_costing_row.id */
  private Long costingRowId;

  /** 命中子件的料号（T9 取价用这个去查价） */
  private String subMaterialCode;

  /** 子件品名（冻住快照，不依赖 raw_hierarchy 后续是否改动） */
  private String subMaterialName;

  /** 命中时的 material_category_1（例如 紫铜盘管 / 紫铜直管） */
  private String subMaterialCategory;

  /** 子件相对父件的用量（来自 raw_hierarchy.qty_per_parent） */
  private BigDecimal subQtyPerParent;

  /** 累计到顶层产品的用量（来自 raw_hierarchy.qty_per_top） */
  private BigDecimal subQtyPerTop;

  /** 追溯字段：指向 raw_hierarchy 具体行 */
  private Long subRawHierarchyId;

  /** 子件的 path（追溯用） */
  private String subPath;

  /** 业务单元（V21 数据隔离，MetaObjectHandler 自动回填） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** 创建时间 */
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  // ============================ getter / setter ============================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getCostingRowId() {
    return costingRowId;
  }

  public void setCostingRowId(Long costingRowId) {
    this.costingRowId = costingRowId;
  }

  public String getSubMaterialCode() {
    return subMaterialCode;
  }

  public void setSubMaterialCode(String subMaterialCode) {
    this.subMaterialCode = subMaterialCode;
  }

  public String getSubMaterialName() {
    return subMaterialName;
  }

  public void setSubMaterialName(String subMaterialName) {
    this.subMaterialName = subMaterialName;
  }

  public String getSubMaterialCategory() {
    return subMaterialCategory;
  }

  public void setSubMaterialCategory(String subMaterialCategory) {
    this.subMaterialCategory = subMaterialCategory;
  }

  public BigDecimal getSubQtyPerParent() {
    return subQtyPerParent;
  }

  public void setSubQtyPerParent(BigDecimal subQtyPerParent) {
    this.subQtyPerParent = subQtyPerParent;
  }

  public BigDecimal getSubQtyPerTop() {
    return subQtyPerTop;
  }

  public void setSubQtyPerTop(BigDecimal subQtyPerTop) {
    this.subQtyPerTop = subQtyPerTop;
  }

  public Long getSubRawHierarchyId() {
    return subRawHierarchyId;
  }

  public void setSubRawHierarchyId(Long subRawHierarchyId) {
    this.subRawHierarchyId = subRawHierarchyId;
  }

  public String getSubPath() {
    return subPath;
  }

  public void setSubPath(String subPath) {
    this.subPath = subPath;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
