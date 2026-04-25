package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * T8 新增：BOM 过滤规则的复合条件 DTO。
 *
 * <p>对应 {@code bom_stop_drill_rule.match_condition_json} 字段，结构：
 *
 * <pre>
 * {
 *   "nodeConditions":   [ {field, op, value|values}, ... ],  // 本节点属性 AND 条件
 *   "parentConditions": [ ... ],                               // 本节点直接父的 AND 条件
 *   "childConditions":  [ ... ]                                // 至少一个直接子满足（OR）
 * }
 * </pre>
 *
 * <p>三组条件合并语义：AND —— 三组都通过才算命中本规则。
 *
 * <p>op 支持：
 * <ul>
 *   <li>{@code EQ} —— 字段等于 value（value 非空）</li>
 *   <li>{@code IN} —— 字段属于 values 列表（values 非空）</li>
 *   <li>{@code LIKE} —— 字段包含 value 子串（预留）</li>
 * </ul>
 *
 * <p>支持的字段名（必须与 BomNodeContext 的字段对应）：
 * <ul>
 *   <li>{@code material_code}</li>
 *   <li>{@code material_name}</li>
 *   <li>{@code material_category_1} / {@code material_category_2}</li>
 *   <li>{@code cost_element_code}</li>
 *   <li>{@code shape_attr}</li>
 *   <li>{@code production_category}</li>
 *   <li>{@code business_unit_type}</li>
 * </ul>
 */
public class DrillRuleCondition {

  private List<Clause> nodeConditions = new ArrayList<>();
  private List<Clause> parentConditions = new ArrayList<>();
  private List<Clause> childConditions = new ArrayList<>();

  public List<Clause> getNodeConditions() {
    return nodeConditions;
  }

  public void setNodeConditions(List<Clause> nodeConditions) {
    this.nodeConditions = nodeConditions;
  }

  public List<Clause> getParentConditions() {
    return parentConditions;
  }

  public void setParentConditions(List<Clause> parentConditions) {
    this.parentConditions = parentConditions;
  }

  public List<Clause> getChildConditions() {
    return childConditions;
  }

  public void setChildConditions(List<Clause> childConditions) {
    this.childConditions = childConditions;
  }

  /** 单条匹配子句。 */
  public static class Clause {

    /** 字段名（必填），见类注释的字段白名单 */
    private String field;

    /** 操作符：EQ / IN / LIKE（必填） */
    private String op;

    /** EQ / LIKE 时的值 */
    private String value;

    /** IN 时的值列表 */
    private List<String> values;

    public String getField() {
      return field;
    }

    public void setField(String field) {
      this.field = field;
    }

    public String getOp() {
      return op;
    }

    public void setOp(String op) {
      this.op = op;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }

    public List<String> getValues() {
      return values;
    }

    public void setValues(List<String> values) {
      this.values = values;
    }
  }
}
