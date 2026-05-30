package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOM 三层架构 · 第 1 层 ODS 原始层：lp_bom_u9_source。
 *
 * <p>U9 / Excel 导入落地层，33 列 U9 原样字段 + 5 列技术字段。不做任何业务加工，
 * 纯粹按行写入，便于追溯和复算。
 *
 * <p>注意：该表**没有** business_unit_type / created_at / updated_at 字段，
 * 审计走 imported_at + imported_by（业务时间，不走 @TableField fill 自动填充）。
 */
@TableName("lp_bom_u9_source")
public class BomU9Source {

  @TableId(type = IdType.AUTO)
  private Long id;

  // ============================ 技术字段（追溯导入批次） ============================

  /** 导入批次 UUID：一次上传一个批次，便于原子性回滚和分批次查询 */
  private String importBatchId;

  /** 数据源类型：EXCEL / U9_API */
  private String sourceType;

  /** Excel 原文件名（U9_API 来源可为空） */
  private String sourceFileName;

  /** 导入时间（业务时间，手工赋值；不走 MetaObjectHandler 自动填充） */
  private LocalDateTime importedAt;

  /** 导入人用户名 */
  private String importedBy;

  // ============================ U9 原样字段（33 列） ============================

  /** 母件料品_料号 */
  private String parentMaterialNo;

  /** 母件料品_品名 */
  private String parentMaterialName;

  /** 生产单位 */
  private String productionUnit;

  /** BOM 生产目的：普机 / 主制造 / 精益 */
  private String bomPurpose;

  /** BOM 版本号：F001 等 */
  private String bomVersion;

  /** BOM 状态：已核准 等 */
  private String bomStatus;

  /** 子件项次 */
  private Integer childSeq;

  /** 子项类型：标准 等 */
  private String childType;

  /** 子件.料号 */
  private String childMaterialNo;

  /** 子项_品名 */
  private String childMaterialName;

  /** 子项规格 */
  private String childMaterialSpec;

  /** 成本要素编码 */
  private String costElementCode;

  /** 成本要素.名称 */
  private String costElementName;

  /** 委托加工备料来源 */
  private String consignSource;

  /**
   * 是否计算成本：1=√，0=空，NULL=未知。
   *
   * <p><b>参考不权威</b>：结算行判定不依赖本字段，仅做运维校验（见 project memory
   * "U9 是否计算成本 列语义未核实"）。
   */
  private Integer u9IsCostFlag;

  /** 工程变更单编码 */
  private String engineeringChangeNo;

  /** 发料单位 */
  private String issueUnit;

  /** 库存主单位 */
  private String stockUnit;

  /** 子项_用量 相对直接父 */
  private BigDecimal qtyPerParent;

  /** 工序号 */
  private String processSeq;

  /**
   * 子件.主分类 第 1 列。
   *
   * <p>DDL 列名为 {@code material_category_1}（数字前有下划线，父设计文档要求）；
   * MP 驼峰转下划线会得到 {@code material_category1}，需手动指定列名。
   */
  @TableField("material_category_1")
  private String materialCategory1;

  /** 子件.主分类 第 2 列，同上需手动指定列名 {@code material_category_2}。 */
  @TableField("material_category_2")
  private String materialCategory2;

  /** 生产分类：制造件 / 采购件 / 半成品 */
  private String productionCategory;

  /** 形态属性 */
  private String shapeAttr;

  /** 生产部门 */
  private String productionDept;

  /** 发料方式 */
  private String issueMethod;

  /** 是否虚拟（TINYINT 1/0） */
  private Integer isVirtual;

  /** 母件底数 */
  private BigDecimal parentBaseQty;

  /** 段 3 替代策略 */
  private String segment3;

  /** 段 4 工序编号 */
  private String segment4;

  /** 订单完工 */
  private Integer orderComplete;

  /** U9 生效日期 */
  private LocalDate effectiveFrom;

  /** U9 失效日期；9999-12-31 = 当前生效 */
  private LocalDate effectiveTo;

  // ============================ getter / setter ============================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(String importBatchId) {
    this.importBatchId = importBatchId;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public LocalDateTime getImportedAt() {
    return importedAt;
  }

  public void setImportedAt(LocalDateTime importedAt) {
    this.importedAt = importedAt;
  }

  public String getImportedBy() {
    return importedBy;
  }

  public void setImportedBy(String importedBy) {
    this.importedBy = importedBy;
  }

  public String getParentMaterialNo() {
    return parentMaterialNo;
  }

  public void setParentMaterialNo(String parentMaterialNo) {
    this.parentMaterialNo = parentMaterialNo;
  }

  public String getParentMaterialName() {
    return parentMaterialName;
  }

  public void setParentMaterialName(String parentMaterialName) {
    this.parentMaterialName = parentMaterialName;
  }

  public String getProductionUnit() {
    return productionUnit;
  }

  public void setProductionUnit(String productionUnit) {
    this.productionUnit = productionUnit;
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

  public Integer getChildSeq() {
    return childSeq;
  }

  public void setChildSeq(Integer childSeq) {
    this.childSeq = childSeq;
  }

  public String getChildType() {
    return childType;
  }

  public void setChildType(String childType) {
    this.childType = childType;
  }

  public String getChildMaterialNo() {
    return childMaterialNo;
  }

  public void setChildMaterialNo(String childMaterialNo) {
    this.childMaterialNo = childMaterialNo;
  }

  public String getChildMaterialName() {
    return childMaterialName;
  }

  public void setChildMaterialName(String childMaterialName) {
    this.childMaterialName = childMaterialName;
  }

  public String getChildMaterialSpec() {
    return childMaterialSpec;
  }

  public void setChildMaterialSpec(String childMaterialSpec) {
    this.childMaterialSpec = childMaterialSpec;
  }

  public String getCostElementCode() {
    return costElementCode;
  }

  public void setCostElementCode(String costElementCode) {
    this.costElementCode = costElementCode;
  }

  public String getCostElementName() {
    return costElementName;
  }

  public void setCostElementName(String costElementName) {
    this.costElementName = costElementName;
  }

  public String getConsignSource() {
    return consignSource;
  }

  public void setConsignSource(String consignSource) {
    this.consignSource = consignSource;
  }

  public Integer getU9IsCostFlag() {
    return u9IsCostFlag;
  }

  public void setU9IsCostFlag(Integer u9IsCostFlag) {
    this.u9IsCostFlag = u9IsCostFlag;
  }

  public String getEngineeringChangeNo() {
    return engineeringChangeNo;
  }

  public void setEngineeringChangeNo(String engineeringChangeNo) {
    this.engineeringChangeNo = engineeringChangeNo;
  }

  public String getIssueUnit() {
    return issueUnit;
  }

  public void setIssueUnit(String issueUnit) {
    this.issueUnit = issueUnit;
  }

  public String getStockUnit() {
    return stockUnit;
  }

  public void setStockUnit(String stockUnit) {
    this.stockUnit = stockUnit;
  }

  public BigDecimal getQtyPerParent() {
    return qtyPerParent;
  }

  public void setQtyPerParent(BigDecimal qtyPerParent) {
    this.qtyPerParent = qtyPerParent;
  }

  public String getProcessSeq() {
    return processSeq;
  }

  public void setProcessSeq(String processSeq) {
    this.processSeq = processSeq;
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

  public String getProductionCategory() {
    return productionCategory;
  }

  public void setProductionCategory(String productionCategory) {
    this.productionCategory = productionCategory;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public String getProductionDept() {
    return productionDept;
  }

  public void setProductionDept(String productionDept) {
    this.productionDept = productionDept;
  }

  public String getIssueMethod() {
    return issueMethod;
  }

  public void setIssueMethod(String issueMethod) {
    this.issueMethod = issueMethod;
  }

  public Integer getIsVirtual() {
    return isVirtual;
  }

  public void setIsVirtual(Integer isVirtual) {
    this.isVirtual = isVirtual;
  }

  public BigDecimal getParentBaseQty() {
    return parentBaseQty;
  }

  public void setParentBaseQty(BigDecimal parentBaseQty) {
    this.parentBaseQty = parentBaseQty;
  }

  public String getSegment3() {
    return segment3;
  }

  public void setSegment3(String segment3) {
    this.segment3 = segment3;
  }

  public String getSegment4() {
    return segment4;
  }

  public void setSegment4(String segment4) {
    this.segment4 = segment4;
  }

  public Integer getOrderComplete() {
    return orderComplete;
  }

  public void setOrderComplete(Integer orderComplete) {
    this.orderComplete = orderComplete;
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
}
