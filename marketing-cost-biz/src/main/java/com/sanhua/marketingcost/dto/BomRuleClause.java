package com.sanhua.marketingcost.dto;

import java.util.List;

/** 新 BOM 结算规则 JSON 条件里的单条子句，替代旧过滤规则 DTO 命名。 */
public class BomRuleClause {

  /** 字段名；必须在新规则评估器白名单内。 */
  private String field;

  /** 操作符：EQ / NE / IN / NOT_IN / LIKE / PREFIX。 */
  private String op;

  /** EQ / NE / LIKE / PREFIX 时的单值。 */
  private String value;

  /** IN / NOT_IN 时的编码或名称清单。 */
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
