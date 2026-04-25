package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOM 三层架构 · 第 2 层 DWD 事实层：lp_bom_raw_hierarchy。
 *
 * <p>在 U9 单层父子基础上派生 path / level / qty_per_top。版本 append-only，
 * 多版本并存，不做 DELETE 历史（见 project memory "BOM 版本 append-only 多版本并存"）。
 *
 * <p>注意：built_at 是业务时间（层级构建时间），手工赋值不走自动填充；
 * business_unit_type 由 MetaObjectHandler 按登录态自动回填（V21 多租户隔离）。
 */
@TableName("lp_bom_raw_hierarchy")
public class BomRawHierarchy {

  @TableId(type = IdType.AUTO)
  private Long id;

  // ============================ 层级定位 ============================

  /** 顶层产品料号 */
  private String topProductCode;

  /** 直接父节点；顶层时等于自己（material_code） */
  private String parentCode;

  /** 当前节点料号 */
  private String materialCode;

  /** 层级：顶层 = 0 */
  private Integer level;

  /** 路径：/top/.../material/ */
  private String path;

  /** U9 子件项次 */
  private Integer sortSeq;

  // ============================ 用量双口径 ============================

  /** 相对直接父用量 */
  private BigDecimal qtyPerParent;

  /** 累计到顶层用量（Builder 连乘计算） */
  private BigDecimal qtyPerTop;

  // ============================ 业务属性（从 u9_source 直接映射） ============================

  /** 料品品名 */
  private String materialName;

  /** 规格 */
  private String materialSpec;

  /** 形态属性 */
  private String shapeAttr;

  /** 生产分类：制造件 / 采购件 / 半成品（对应 u9_source.production_category） */
  private String sourceCategory;

  /** 成本要素编码 */
  private String costElementCode;

  /** T8 新增：U9 子件主分类第 1 列（紫铜盘管 / 紫铜直管 / 铝盘管 等）
   *  从 u9_source 透传；规则复合条件 childConditions 用这个字段判命中。
   *  显式指定列名 —— MP 默认 camelCase → snake_case 在数字前不加下划线，
   *  会变成 "material_category1"，但 DB 列是 "material_category_1"。 */
  @TableField("material_category_1")
  private String materialCategory1;

  /** T8 新增：U9 子件主分类第 2 列（Excel 里两列同名"子件.主分类"） */
  @TableField("material_category_2")
  private String materialCategory2;

  /** BOM 生产目的；手工 / 电子图库源留 NULL */
  private String bomPurpose;

  /** BOM 版本号 */
  private String bomVersion;

  /** BOM 状态 */
  private String bomStatus;

  /** 是否计算成本（参考不权威，同 u9_source） */
  private Integer u9IsCostFlag;

  /** 是否叶子：无子件则为 1（Builder 派生） */
  private Integer isLeaf;

  // ============================ 时效（U9 原样继承，支持多版本并存） ============================

  /** BOM 版本起始日期 */
  private LocalDate effectiveFrom;

  /** BOM 版本结束日期；NULL 或 9999-12-31 = 当前生效 */
  private LocalDate effectiveTo;

  // ============================ 数据源标识 ============================

  /** 数据源类型：U9 / MANUAL / E_DRAWING；本期只有 U9 */
  private String sourceType;

  // ============================ 批次追溯 ============================

  /** 来自 u9_source 的导入批次 */
  private String sourceImportBatchId;

  /** 本次层级构建批次 */
  private String buildBatchId;

  /** 层级构建时间（业务时间，手工赋值） */
  private LocalDateTime builtAt;

  // ============================ 业务单元隔离（V21） ============================

  /** 业务单元：COMMERCIAL / HOUSEHOLD；insert 时由 MetaObjectHandler 自动回填 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  // ============================ getter / setter ============================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTopProductCode() {
    return topProductCode;
  }

  public void setTopProductCode(String topProductCode) {
    this.topProductCode = topProductCode;
  }

  public String getParentCode() {
    return parentCode;
  }

  public void setParentCode(String parentCode) {
    this.parentCode = parentCode;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public Integer getLevel() {
    return level;
  }

  public void setLevel(Integer level) {
    this.level = level;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public Integer getSortSeq() {
    return sortSeq;
  }

  public void setSortSeq(Integer sortSeq) {
    this.sortSeq = sortSeq;
  }

  public BigDecimal getQtyPerParent() {
    return qtyPerParent;
  }

  public void setQtyPerParent(BigDecimal qtyPerParent) {
    this.qtyPerParent = qtyPerParent;
  }

  public BigDecimal getQtyPerTop() {
    return qtyPerTop;
  }

  public void setQtyPerTop(BigDecimal qtyPerTop) {
    this.qtyPerTop = qtyPerTop;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getMaterialSpec() {
    return materialSpec;
  }

  public void setMaterialSpec(String materialSpec) {
    this.materialSpec = materialSpec;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public String getSourceCategory() {
    return sourceCategory;
  }

  public void setSourceCategory(String sourceCategory) {
    this.sourceCategory = sourceCategory;
  }

  public String getCostElementCode() {
    return costElementCode;
  }

  public void setCostElementCode(String costElementCode) {
    this.costElementCode = costElementCode;
  }

  public String getMaterialCategory1() {
    return materialCategory1;
  }

  public void setMaterialCategory1(String materialCategory1) {
    this.materialCategory1 = materialCategory1;
  }

  public String getMaterialCategory2() {
    return materialCategory2;
  }

  public void setMaterialCategory2(String materialCategory2) {
    this.materialCategory2 = materialCategory2;
  }

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
  }

  public String getBomVersion() {
    return bomVersion;
  }

  public void setBomVersion(String bomVersion) {
    this.bomVersion = bomVersion;
  }

  public String getBomStatus() {
    return bomStatus;
  }

  public void setBomStatus(String bomStatus) {
    this.bomStatus = bomStatus;
  }

  public Integer getU9IsCostFlag() {
    return u9IsCostFlag;
  }

  public void setU9IsCostFlag(Integer u9IsCostFlag) {
    this.u9IsCostFlag = u9IsCostFlag;
  }

  public Integer getIsLeaf() {
    return isLeaf;
  }

  public void setIsLeaf(Integer isLeaf) {
    this.isLeaf = isLeaf;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceImportBatchId() {
    return sourceImportBatchId;
  }

  public void setSourceImportBatchId(String sourceImportBatchId) {
    this.sourceImportBatchId = sourceImportBatchId;
  }

  public String getBuildBatchId() {
    return buildBatchId;
  }

  public void setBuildBatchId(String buildBatchId) {
    this.buildBatchId = buildBatchId;
  }

  public LocalDateTime getBuiltAt() {
    return builtAt;
  }

  public void setBuiltAt(LocalDateTime builtAt) {
    this.builtAt = builtAt;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }
}
