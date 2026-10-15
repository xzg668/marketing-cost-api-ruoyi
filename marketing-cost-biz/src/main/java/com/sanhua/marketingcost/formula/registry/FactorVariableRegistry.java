package com.sanhua.marketingcost.formula.registry;

import java.math.BigDecimal;
import java.util.Optional;

/** 技术公式使用的变量词典、派生规则及行内绑定。未命中必须由调用方报告缺变量，不能补0。 */
public interface FactorVariableRegistry {
  Optional<BigDecimal> resolve(String variableCode, VariableContext ctx);
}
