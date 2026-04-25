package com.sanhua.marketingcost.formula.registry.resolvers;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.formula.registry.VariableResolver;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 金属基价解析器（T26c）—— 按权威源从 {@code lp_finance_base_price} 精确查价。
 *
 * <p>语义定位：OA 锁价路径（由 Calc/Preview 服务将 oaForm 展开到 {@code ctx.overrides}
 * 处理，优先级高于任何 resolver）之下，金属基价变量（Cu/Zn/Sn/Al/Cn）的正式数据源。
 *
 * <p>查询键（四键精确匹配）：
 * <ol>
 *   <li>{@code factor_code} —— 来自 variable 的 {@code context_binding_json.factorCode}</li>
 *   <li>{@code price_month} —— 来自 {@code ctx.pricingMonth}</li>
 *   <li>{@code price_source} —— 来自 variable 的 {@code context_binding_json.priceSource}
 *       （如 "长江现货平均价" / "SMM平均价"）</li>
 *   <li>{@code business_unit_type} —— 来自 {@link BusinessUnitContext}（Mapper 层未挂
 *       {@code @DataScope}，在此显式 eq 保证租户隔离）</li>
 * </ol>
 *
 * <p>严格无兜底：
 * <ul>
 *   <li>四键查不到 → 返回 null，由上层标记 MISSING —— 目的是把"财务未按权威源
 *       导入该月数据"暴露到页面，避免静默降级用了次级源导致算错</li>
 *   <li>priceMonth 缺失、factorCode 缺失、BU 缺失任何一项 → 直接返回 null 并记 warn</li>
 *   <li>不做"月份最新回退"或"priceSource 降级"之类的隐式 fallback</li>
 * </ul>
 */
@Component
public class FinanceBaseResolver implements VariableResolver {

  private static final Logger log = LoggerFactory.getLogger(FinanceBaseResolver.class);

  private static final TypeReference<Map<String, Object>> BINDING_TYPE =
      new TypeReference<>() {};

  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final ObjectMapper objectMapper;

  public FinanceBaseResolver(
      FinanceBasePriceMapper financeBasePriceMapper, ObjectMapper objectMapper) {
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public String sourceType() {
    return "FINANCE_BASE";
  }

  @Override
  public BigDecimal resolve(PriceVariable variable, VariableContext ctx) {
    if (variable == null || ctx == null) {
      return null;
    }
    // 1) 解析 context_binding_json，拿到 factorCode + priceSource
    Map<String, Object> binding = parseBinding(variable);
    if (binding == null) {
      log.warn("FINANCE_BASE 变量 {} 缺 context_binding_json，无法路由基价表",
          variable.getVariableCode());
      return null;
    }
    String factorCode = asString(binding.get("factorCode"));
    String priceSource = asString(binding.get("priceSource"));
    if (isBlank(factorCode) || isBlank(priceSource)) {
      log.warn("FINANCE_BASE 变量 {} context_binding_json 缺 factorCode 或 priceSource: {}",
          variable.getVariableCode(), binding);
      return null;
    }

    // 2) 拿 pricingMonth（必填）
    String priceMonth = ctx.getPricingMonth();
    if (isBlank(priceMonth)) {
      log.warn("FINANCE_BASE 变量 {} 缺 ctx.pricingMonth，无法精确到月份",
          variable.getVariableCode());
      return null;
    }

    // 3) 拿当前租户 BU（必填，避免跨 BU 污染）
    String bu = BusinessUnitContext.getCurrentBusinessUnitType();
    if (isBlank(bu)) {
      log.warn("FINANCE_BASE 变量 {} 当前请求无 businessUnitType（系统级调用？），拒绝查询",
          variable.getVariableCode());
      return null;
    }

    // 4) 四键精确查 —— 绝不做 priceSource / month 的模糊降级
    FinanceBasePrice row = financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getFactorCode, factorCode.trim())
            .eq(FinanceBasePrice::getPriceMonth, priceMonth.trim())
            .eq(FinanceBasePrice::getPriceSource, priceSource.trim())
            .eq(FinanceBasePrice::getBusinessUnitType, bu)
            .orderByDesc(FinanceBasePrice::getId)
            .last("LIMIT 1"));
    if (row == null) {
      log.info("FINANCE_BASE 变量 {} 四键未命中: factor_code={}, price_month={}, "
              + "price_source={}, bu={}（请确认财务已导入该月权威源数据）",
          variable.getVariableCode(), factorCode, priceMonth, priceSource, bu);
      return null;
    }
    return row.getPrice();
  }

  /** 解析 PriceVariable.context_binding_json 为 Map；null/空字符串/非法 JSON 返回 null */
  private Map<String, Object> parseBinding(PriceVariable variable) {
    String json = variable.getContextBindingJson();
    if (isBlank(json)) {
      return null;
    }
    try {
      return objectMapper.readValue(json, BINDING_TYPE);
    } catch (Exception e) {
      log.warn("FINANCE_BASE 变量 {} 的 context_binding_json 解析失败: {} —— {}",
          variable.getVariableCode(), json, e.getMessage());
      return null;
    }
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
