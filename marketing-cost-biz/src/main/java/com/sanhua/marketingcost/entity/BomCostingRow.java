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
 * BOM 三层架构 · 第 3 层 DWS 业务视图层：lp_bom_costing_row。
 *
 * <p>拍平后的结算行，新表替代老表 lp_bom_manage_item。绑定 OA + as_of_date 锁版本，
 * 支持月度冻结复算（见 project memory "BOM 版本 append-only 多版本并存"）。
 *
 * <p>字段分工：
 * <ul>
 *   <li>built_at：业务时间，拍平执行那一刻，手工赋值</li>
 *   <li>created_at / updated_at：审计时间，走 MetaObjectHandler 自动填充</li>
 *   <li>business_unit_type：V21 多租户，MetaObjectHandler 自动回填</li>
 * </ul>
 *
 * <p>严禁复活老表坏味道字段：copper_price_tax / zinc_price_tax / product_name /
 * filter_rule / material / source。这些字段的语义应归入取价快照或 oa_form。
 */
@TableName("lp_bom_costing_row")
public class BomCostingRow {

  @TableId(type = IdType.AUTO)
  private Long id;

  // ============================ 关联 OA ============================

  /** 所属 OA 单号 */
  private String oaNo;

  // ============================ 结构定位 ============================

  /** 顶层产品料号 */
  private String topProductCode;

  /** 直接父节点；顶层时为 NULL */
  private String parentCode;

  /** 当前结算行料号 */
  private String materialCode;

  /** 层级：顶层 = 0 */
  private Integer level;

  /** 路径：/top/.../material/ */
  private String path;

  // ============================ 用量 ============================

  /** 相对直接父用量 */
  private BigDecimal qtyPerParent;

  /** 累计到顶层用量 */
  private BigDecimal qtyPerTop;

  // ============================ 结算行标记 ============================

  /** 是否结算行（当前规则下本表所有行都是结算行，保留字段便于未来扩展） */
  private Integer isCostingRow;

  /** 下游是否走子树算法（见 project memory "接管子树算法在取价阶段做"） */
  private Integer subtreeCostRequired;

  // ============================ 追溯 ============================

  /** 指向 lp_bom_raw_hierarchy.id（软引用，未建外键） */
  private Long rawHierarchyNodeId;

  /** 命中的过滤规则 id（未建外键） */
  private Long matchedDrillRuleId;

  // ============================ 业务属性（拍平时从 raw_hierarchy 拷贝） ============================

  /** 料品品名 */
  private String materialName;

  /** 规格 */
  private String materialSpec;

  /** 形态属性 */
  private String shapeAttr;

  /** 生产分类 */
  private String sourceCategory;

  /** 成本要素编码 */
  private String costElementCode;

  /** BOM 生产目的 */
  private String bomPurpose;

  /** BOM 版本号 */
  private String bomVersion;

  /** 是否计算成本（参考不权威） */
  private Integer u9IsCostFlag;

  /** BOM 版本起始日期 */
  private LocalDate effectiveFrom;

  /** BOM 版本结束日期 */
  private LocalDate effectiveTo;

  // ============================ 批次追溯 ============================

  /** 拍平批次 ID */
  private String buildBatchId;

  /** 拍平时间（业务时间，手工赋值） */
  private LocalDateTime builtAt;

  // ============================ 版本锁定（锁月核心） ============================

  /** 本次拍平使用的 BOM 版本基准日期 */
  private LocalDate asOfDate;

  /** 冻住 raw_hierarchy 行的 effective_from，用于复算时精确定位到哪一版 */
  private LocalDate rawVersionEffectiveFrom;

  // ============================ 业务单元隔离（V21） ============================

  /** insert 时由 MetaObjectHandler 自动回填 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  // ============================ 审计（自动填充） ============================

  /** 创建时间，MetaObjectHandler 自动填充 */
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  /** 更新时间，MetaObjectHandler 自动填充 */
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  // ============================ getter / setter ============================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
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

  public Integer getIsCostingRow() {
    return isCostingRow;
  }

  public void setIsCostingRow(Integer isCostingRow) {
    this.isCostingRow = isCostingRow;
  }

  public Integer getSubtreeCostRequired() {
    return subtreeCostRequired;
  }

  public void setSubtreeCostRequired(Integer subtreeCostRequired) {
    this.subtreeCostRequired = subtreeCostRequired;
  }

  public Long getRawHierarchyNodeId() {
    return rawHierarchyNodeId;
  }

  public void setRawHierarchyNodeId(Long rawHierarchyNodeId) {
    this.rawHierarchyNodeId = rawHierarchyNodeId;
  }

  public Long getMatchedDrillRuleId() {
    return matchedDrillRuleId;
  }

  public void setMatchedDrillRuleId(Long matchedDrillRuleId) {
    this.matchedDrillRuleId = matchedDrillRuleId;
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

  public Integer getU9IsCostFlag() {
    return u9IsCostFlag;
  }

  public void setU9IsCostFlag(Integer u9IsCostFlag) {
    this.u9IsCostFlag = u9IsCostFlag;
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

  public LocalDate getAsOfDate() {
    return asOfDate;
  }

  public void setAsOfDate(LocalDate asOfDate) {
    this.asOfDate = asOfDate;
  }

  public LocalDate getRawVersionEffectiveFrom() {
    return rawVersionEffectiveFrom;
  }

  public void setRawVersionEffectiveFrom(LocalDate rawVersionEffectiveFrom) {
    this.rawVersionEffectiveFrom = rawVersionEffectiveFrom;
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

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
