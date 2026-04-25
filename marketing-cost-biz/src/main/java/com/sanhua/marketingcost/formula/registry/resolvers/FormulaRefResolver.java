package com.sanhua.marketingcost.formula.registry.resolvers;

import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 公式引用解析器 —— 变量值由另一个公式表达式计算得到。
 *
 * <p>例如 {@code Cu_excl = [Cu] / (1 + [vat_rate])}，本 resolver 会：
 * <ol>
 *   <li>抽出表达式中的 {@code [Cu]} 与 {@code [vat_rate]} 子变量</li>
 *   <li>对每个子变量调用 {@link VariableRegistry#resolveInternal}（带 DFS 栈以检测环）</li>
 *   <li>用 {@link ExpressionEvaluator} 求最终值</li>
 * </ol>
 *
 * <p>实现 {@link VariableRegistry.RecursiveAware}：Registry 调度时会传入 stack/cache，
 * 子调用复用同一栈，能正确侦测多层闭环（A→B→C→A）。
 */
@Component
public class FormulaRefResolver implements VariableResolver, VariableRegistry.RecursiveAware {

  private static final Logger log = LoggerFactory.getLogger(FormulaRefResolver.class);

  @Override
  public String sourceType() {
    return "FORMULA_REF";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    // 不带 stack 的入口（不应被直接调用），转发到带新栈的版本仅做单层求值
    return resolveRecursive(variable, ctx, null,
        new LinkedHashSet<>(), new HashMap<>());
  }

  @Override
  public BigDecimal resolveRecursive(
      PriceVariable variable,
      VariableContext ctx,
      VariableRegistry registry,
      LinkedHashSet<String> stack,
      Map<String, BigDecimal> requestCache) {
    if (variable == null || variable.getFormulaExpr() == null
        || variable.getFormulaExpr().isBlank()) {
      log.warn("FormulaRefResolver 变量未配置 formula_expr: code={}",
          variable == null ? null : variable.getVariableCode());
      return null;
    }
    String expr = variable.getFormulaExpr().trim();
    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables(expr);
    Map<String, BigDecimal> values = new HashMap<>();
    for (String token : tokens) {
      BigDecimal v;
      if (registry != null) {
        v = registry.resolveInternal(token, ctx, stack, requestCache);
      } else {
        // 回退路径（resolve 未带 registry 时，子变量按 0 处理）
        v = null;
      }
      values.put(token, v == null ? BigDecimal.ZERO : v);
    }
    return ExpressionEvaluator.evaluate(expr, values);
  }
}
