package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOM 过滤规则：bom_stop_drill_rule。
 *
 * <p>拍平阶段（阶段 C）按 match_type / match_value 命中后打标。配合
 * application.yml 里 {@code mybatis-plus.global-config.db-config.logic-delete-field=deleted}，
 * deleted 字段由 {@link TableLogic} 处理软删。
 */
@TableName("bom_stop_drill_rule")
public class BomStopDrillRule {

  @TableId(type = IdType.AUTO)
  private Long id;

  // ============================ 匹配条件 ============================

  /** 匹配类型：NAME_LIKE / MATERIAL_CODE_PREFIX / MATERIAL_TYPE / CATEGORY_EQ / SHAPE_ATTR_EQ
   *  / COMPOSITE（T8 新增：占位值，实际条件读 matchConditionJson） */
  private String matchType;

  /** 匹配值（单字段时存具体值；COMPOSITE 时只存占位字符串，实际条件在 matchConditionJson）*/
  private String matchValue;

  /** T8 新增：复合条件 JSON。
   *  非空时优先于 matchType/matchValue；结构见 DrillRuleCondition DTO。
   *  数据库类型 JSON；MyBatis-Plus 以 String 映射，上层用 Jackson 反序列化。 */
  private String matchConditionJson;

  // ============================ 动作 ============================

  /** 命中后的动作：STOP_AND_COST_ROW / EXCLUDE / REPLACE / ROLLUP_TO_PARENT（T8 新增）*/
  private String drillAction;

  /** 命中后是否打 subtree_cost_required=1（联动类为 0，接管类为 1） */
  private Integer markSubtreeCostRequired;

  /** REPLACE 动作时的目标料号，其他动作可为空 */
  private String replaceToCode;

  // ============================ 优先级 / 时效 ============================

  /** 数字越小优先级越高 */
  private Integer priority;

  /** 是否启用（1=启用，0=停用） */
  private Integer enabled;

  /** 规则生效起 */
  private LocalDate effectiveFrom;

  /** 规则生效止 */
  private LocalDate effectiveTo;

  /** 业务单元：NULL=全局规则，COMMERCIAL / HOUSEHOLD 限定单元 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  // ============================ 审计 ============================

  /** 规则说明 */
  private String remark;

  /** 创建人用户名（手工赋值） */
  private String createdBy;

  /** 创建时间（MetaObjectHandler 自动填充） */
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  /** 更新人用户名（手工赋值） */
  private String updatedBy;

  /** 更新时间（MetaObjectHandler 自动填充） */
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  /** 软删标记；@TableLogic 让 selectById 自动过滤 deleted=1 的行 */
  @TableLogic
  private Integer deleted;

  // ============================ getter / setter ============================

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getMatchType() {
    return matchType;
  }

  public void setMatchType(String matchType) {
    this.matchType = matchType;
  }

  public String getMatchValue() {
    return matchValue;
  }

  public void setMatchValue(String matchValue) {
    this.matchValue = matchValue;
  }

  public String getMatchConditionJson() {
    return matchConditionJson;
  }

  public void setMatchConditionJson(String matchConditionJson) {
    this.matchConditionJson = matchConditionJson;
  }

  public String getDrillAction() {
    return drillAction;
  }

  public void setDrillAction(String drillAction) {
    this.drillAction = drillAction;
  }

  public Integer getMarkSubtreeCostRequired() {
    return markSubtreeCostRequired;
  }

  public void setMarkSubtreeCostRequired(Integer markSubtreeCostRequired) {
    this.markSubtreeCostRequired = markSubtreeCostRequired;
  }

  public String getReplaceToCode() {
    return replaceToCode;
  }

  public void setReplaceToCode(String replaceToCode) {
    this.replaceToCode = replaceToCode;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public Integer getEnabled() {
    return enabled;
  }

  public void setEnabled(Integer enabled) {
    this.enabled = enabled;
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

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getDeleted() {
    return deleted;
  }

  public void setDeleted(Integer deleted) {
    this.deleted = deleted;
  }
}
