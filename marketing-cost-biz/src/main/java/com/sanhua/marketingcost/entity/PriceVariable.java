package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_price_variable")
public class PriceVariable {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String variableCode;
  private String variableName;
  private String sourceType;
  private String sourceTable;
  private String sourceField;
  private String scope;
  private String status;
  /** 税口径：INCL=含税 / EXCL=不含税 / NONE=无税 (V13) */
  private String taxMode;
  /** CONST 类型默认值 (V13) */
  private BigDecimal defaultValue;
  /** FORMULA_REF 类型公式表达式（含 [变量] 引用，V13） */
  private String formulaExpr;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  private String businessUnitType;

  /** V24 三层模型分类：FINANCE_FACTOR / PART_CONTEXT / FORMULA_REF / CONST */
  @TableField("factor_type")
  private String factorType;

  /** V24 中文/符号别名 JSON 数组（供 FormulaNormalizer 扫描用） */
  @TableField("aliases_json")
  private String aliasesJson;

  /** V24 PART_CONTEXT 字段绑定或派生策略 JSON */
  @TableField("context_binding_json")
  private String contextBindingJson;

  /**
   * V31 统一解析器分发键：FINANCE / ENTITY / DERIVED / FORMULA / CONST。
   * 与 {@link #factorType} 正交 —— factorType 是前端 UI 分组标签，
   * resolverKind 才是后端 FactorVariableRegistryImpl 的分支选择依据。
   */
  @TableField("resolver_kind")
  private String resolverKind;

  /**
   * V31 解析参数 JSON，按 {@link #resolverKind} 自描述形态。契约示例：
   * <pre>
   * FINANCE : {"factorCode":"Cu","priceSource":"平均价","buScoped":true}
   *           或 {"shortName":"美国柜装黄铜","priceSource":"平均价","buScoped":true}
   * ENTITY  : {"entity":"linkedItem","field":"blankWeight","unitScale":0.001}
   * DERIVED : {"strategy":"MAIN_MATERIAL_FINANCE"} 等 4 种 strategy
   * FORMULA : {"expr":"[Cu]/(1+[vat_rate])"}
   * CONST   : {"value":"0.13"}
   * </pre>
   * 存原始 JSON 字符串，和 {@link #contextBindingJson} 同风格，
   * 由调用方按需用 ObjectMapper 反序列化。
   */
  @TableField("resolver_params")
  private String resolverParams;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getVariableCode() {
    return variableCode;
  }

  public void setVariableCode(String variableCode) {
    this.variableCode = variableCode;
  }

  public String getVariableName() {
    return variableName;
  }

  public void setVariableName(String variableName) {
    this.variableName = variableName;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceTable() {
    return sourceTable;
  }

  public void setSourceTable(String sourceTable) {
    this.sourceTable = sourceTable;
  }

  public String getSourceField() {
    return sourceField;
  }

  public void setSourceField(String sourceField) {
    this.sourceField = sourceField;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getTaxMode() {
    return taxMode;
  }

  public void setTaxMode(String taxMode) {
    this.taxMode = taxMode;
  }

  public BigDecimal getDefaultValue() {
    return defaultValue;
  }

  public void setDefaultValue(BigDecimal defaultValue) {
    this.defaultValue = defaultValue;
  }

  public String getFormulaExpr() {
    return formulaExpr;
  }

  public void setFormulaExpr(String formulaExpr) {
    this.formulaExpr = formulaExpr;
  }

  public String getFactorType() {
    return factorType;
  }

  public void setFactorType(String factorType) {
    this.factorType = factorType;
  }

  public String getAliasesJson() {
    return aliasesJson;
  }

  public void setAliasesJson(String aliasesJson) {
    this.aliasesJson = aliasesJson;
  }

  public String getContextBindingJson() {
    return contextBindingJson;
  }

  public void setContextBindingJson(String contextBindingJson) {
    this.contextBindingJson = contextBindingJson;
  }

  public String getResolverKind() {
    return resolverKind;
  }

  public void setResolverKind(String resolverKind) {
    this.resolverKind = resolverKind;
  }

  public String getResolverParams() {
    return resolverParams;
  }

  public void setResolverParams(String resolverParams) {
    this.resolverParams = resolverParams;
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
