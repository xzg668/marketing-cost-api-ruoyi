package com.sanhua.marketingcost.formula.registry;

import com.sanhua.marketingcost.entity.PriceVariable;
import java.math.BigDecimal;

/**
 * 变量解析器 —— 按 {@link PriceVariable#getSourceType()} 路由到 9 个实现之一。
 *
 * <p>所有实现都是 {@code @Component}，由 {@link VariableRegistry} 通过 Spring 注入收集。
 * 实现必须无副作用（线程安全），可被并发调用。
 *
 * <p>未命中（数据缺失）返回 {@code null}；非法配置抛 {@link IllegalArgumentException}；
 * 不要返回 {@link BigDecimal#ZERO} 掩盖问题。
 */
public interface VariableResolver {

  /** 与 {@code lp_price_variable.source_type} 匹配的字符串（如 OA_FORM/CONST/FORMULA_REF） */
  String sourceType();

  /**
   * 解析变量值。
   *
   * @param variable 变量元数据（含 sourceTable / sourceField / formulaExpr 等）
   * @param ctx      取价请求上下文
   * @return 不含税或含税数值（按 variable.taxMode 声明），无数据返回 null
   */
  BigDecimal resolve(PriceVariable variable, VariableContext ctx);
}
