package com.sanhua.marketingcost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 价格变量写入请求 —— 新增 / 更新 共用。
 *
 * <p>服务层负责：
 * <ol>
 *   <li>{@code variableCode} 全局唯一（新增时）/ 不可改（更新时）</li>
 *   <li>{@code factorType} ∈ {FINANCE_FACTOR, PART_CONTEXT, FORMULA_REF, CONST}</li>
 *   <li>{@code resolverKind} ∈ {FINANCE, ENTITY, DERIVED, FORMULA, CONST}</li>
 *   <li>{@code resolverParams} 按 {@code resolverKind} 做 schema 校验（必填字段齐全）</li>
 * </ol>
 * JSON 层失败走 {@code GlobalExceptionHandler} 的 {@code IllegalArgumentException} 分支 → 400。
 */
public class PriceVariableRequest {

  /** 机器码 —— 英文下划线，例：{@code Cu} / {@code blank_weight} */
  @NotBlank
  @Pattern(
      regexp = "^[A-Za-z][A-Za-z0-9_]*$",
      message = "variableCode 只能字母开头，后续字母/数字/下划线")
  private String variableCode;

  /** 显示名 —— 中文，例："电解铜" / "下料重量" */
  @NotBlank
  private String variableName;

  /** 中文别名 JSON 数组字符串，例：{@code ["电解铜","Cu"]} —— FormulaNormalizer 识别依据 */
  private String aliasesJson;

  /** UI 分组标签：FINANCE_FACTOR / PART_CONTEXT / FORMULA_REF / CONST */
  @NotBlank
  private String factorType;

  /** 后端解析分发键：FINANCE / ENTITY / DERIVED / FORMULA / CONST */
  @NotBlank
  private String resolverKind;

  /** 按 resolverKind 自描述的参数对象；服务层做 schema 校验 */
  private Map<String, Object> resolverParams;

  /** 税口径：INCL / EXCL / NONE —— 仅展示用，不参与 resolver 分发 */
  private String taxMode;

  /** 业务单元：COMMERCIAL / HOUSEHOLD / null */
  private String businessUnitType;

  /** 状态：active / inactive —— 默认 active */
  private String status;

  /** 作用域标注（legacy 保留字段，业务上已淡化） */
  private String scope;

  /** CONST 默认值 —— 可选；新模型下 CONST 参数通过 resolverParams.value 下发，本字段仅兼容展示 */
  private BigDecimal defaultValue;

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

  public String getAliasesJson() {
    return aliasesJson;
  }

  public void setAliasesJson(String aliasesJson) {
    this.aliasesJson = aliasesJson;
  }

  public String getFactorType() {
    return factorType;
  }

  public void setFactorType(String factorType) {
    this.factorType = factorType;
  }

  public String getResolverKind() {
    return resolverKind;
  }

  public void setResolverKind(String resolverKind) {
    this.resolverKind = resolverKind;
  }

  public Map<String, Object> getResolverParams() {
    return resolverParams;
  }

  public void setResolverParams(Map<String, Object> resolverParams) {
    this.resolverParams = resolverParams;
  }

  public String getTaxMode() {
    return taxMode;
  }

  public void setTaxMode(String taxMode) {
    this.taxMode = taxMode;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public BigDecimal getDefaultValue() {
    return defaultValue;
  }

  public void setDefaultValue(BigDecimal defaultValue) {
    this.defaultValue = defaultValue;
  }
}
