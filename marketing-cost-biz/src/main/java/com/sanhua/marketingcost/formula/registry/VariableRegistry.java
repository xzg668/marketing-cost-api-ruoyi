package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 变量注册表 —— 公式变量解析的唯一入口。
 *
 * <p>职责：
 * <ol>
 *   <li>启动时按 {@code sourceType} 收集所有 {@link VariableResolver} bean 形成路由表</li>
 *   <li>{@link #resolve(String, VariableContext)} 根据 variable_code 查元数据 → 路由 resolver</li>
 *   <li>维护 DFS 解析栈以检测公式变量循环引用（{@link CircularFormulaException}）</li>
 *   <li>缓存单次请求内的变量值（同一 ctx 内同变量只解析一次）</li>
 * </ol>
 *
 * <p>变量元数据从 {@code lp_price_variable} 表加载；首次访问时缓存到内存 map，
 * 不主动失效（变量定义本身极少变更，运维改完重启服务即可）。
 */
@Component
public class VariableRegistry {

  private static final Logger log = LoggerFactory.getLogger(VariableRegistry.class);

  private final PriceVariableMapper priceVariableMapper;

  /** sourceType → resolver 路由表 */
  private final Map<String, VariableResolver> routes;

  /** variable_code → 元数据缓存（lazy load） */
  private volatile Map<String, PriceVariable> variableCache;

  public VariableRegistry(
      PriceVariableMapper priceVariableMapper, List<VariableResolver> resolvers) {
    this.priceVariableMapper = priceVariableMapper;
    Map<String, VariableResolver> map = new HashMap<>();
    for (VariableResolver resolver : resolvers) {
      String type = resolver.sourceType();
      if (type == null || type.isBlank()) {
        log.warn("跳过未声明 sourceType 的 resolver: {}", resolver.getClass().getName());
        continue;
      }
      VariableResolver previous = map.put(type.trim().toUpperCase(), resolver);
      if (previous != null) {
        log.warn("sourceType={} 存在多个 resolver: {} → {}",
            type, previous.getClass().getName(), resolver.getClass().getName());
      }
    }
    this.routes = Map.copyOf(map);
  }

  /**
   * 解析单个变量值，支持递归与循环检测。
   *
   * <p>典型用法：
   * <pre>{@code
   * BigDecimal cu = registry.resolve("Cu", new VariableContext().oaForm(form));
   * }</pre>
   */
  public BigDecimal resolve(String variableCode, VariableContext ctx) {
    if (variableCode == null || variableCode.isBlank()) {
      return null;
    }
    return resolveInternal(variableCode.trim(), ctx, new LinkedHashSet<>(), new HashMap<>());
  }

  /**
   * 批量解析多个变量值（同一上下文内复用解析栈与缓存）。
   *
   * @return 变量名 → 数值映射；缺失的变量值为 null
   */
  public Map<String, BigDecimal> resolveAll(List<String> variableCodes, VariableContext ctx) {
    Map<String, BigDecimal> values = new HashMap<>();
    if (variableCodes == null || variableCodes.isEmpty()) {
      return values;
    }
    Map<String, BigDecimal> cache = new HashMap<>();
    for (String code : variableCodes) {
      if (code == null || code.isBlank()) {
        continue;
      }
      String trimmed = code.trim();
      values.put(trimmed,
          resolveInternal(trimmed, ctx, new LinkedHashSet<>(), cache));
    }
    return values;
  }

  /** 内部递归入口 —— 维护 DFS 栈检测环 + 单次请求缓存。仅供 RecursiveAware resolver 调用 */
  public BigDecimal resolveInternal(
      String code,
      VariableContext ctx,
      LinkedHashSet<String> stack,
      Map<String, BigDecimal> requestCache) {
    // 显式覆盖优先级最高
    if (ctx != null && ctx.getOverrides().containsKey(code)) {
      return ctx.getOverrides().get(code);
    }
    if (requestCache.containsKey(code)) {
      return requestCache.get(code);
    }
    if (!stack.add(code)) {
      // 已在栈中 → 环；裁出从环起点到当前的子路径
      List<String> path = new ArrayList<>(stack);
      int start = path.indexOf(code);
      List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
      cycle.add(code); // 闭环
      throw new CircularFormulaException(cycle);
    }
    try {
      PriceVariable variable = lookup(code);
      if (variable == null) {
        log.debug("VariableRegistry 未找到变量定义: code={}", code);
        return null;
      }
      String sourceType = variable.getSourceType();
      if (sourceType == null) {
        log.warn("变量 {} 未声明 sourceType，跳过", code);
        return null;
      }
      VariableResolver resolver = routes.get(sourceType.trim().toUpperCase());
      if (resolver == null) {
        log.warn("变量 {} 的 sourceType={} 没有对应 resolver", code, sourceType);
        return null;
      }
      BigDecimal value;
      if (resolver instanceof RecursiveAware aware) {
        // FormulaRef 走递归解析路径
        value = aware.resolveRecursive(variable, ctx, this, stack, requestCache);
      } else {
        value = resolver.resolve(variable, ctx);
      }
      requestCache.put(code, value);
      return value;
    } finally {
      stack.remove(code);
    }
  }

  /** 通过 variable_code 查元数据，懒加载并缓存 */
  PriceVariable lookup(String code) {
    Map<String, PriceVariable> cache = variableCache;
    if (cache == null) {
      synchronized (this) {
        cache = variableCache;
        if (cache == null) {
          cache = loadAll();
          variableCache = cache;
        }
      }
    }
    return cache.get(code);
  }

  private Map<String, PriceVariable> loadAll() {
    List<PriceVariable> rows = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class).eq(PriceVariable::getStatus, "active"));
    Map<String, PriceVariable> map = new HashMap<>();
    for (PriceVariable row : rows) {
      if (row.getVariableCode() != null) {
        map.put(row.getVariableCode().trim(), row);
      }
    }
    log.info("VariableRegistry 加载 {} 个变量定义", map.size());
    return map;
  }

  /** 测试用：清空缓存以便重读 */
  void invalidate() {
    this.variableCache = null;
  }

  /**
   * 标记为递归感知的 resolver —— FormulaRef 实现该接口拿到 Registry / DFS 栈 / 缓存，
   * 以便对子变量调用 {@code registry.resolveInternal(...)} 而不丢失环检测信息。
   */
  public interface RecursiveAware {
    BigDecimal resolveRecursive(
        PriceVariable variable,
        VariableContext ctx,
        VariableRegistry registry,
        LinkedHashSet<String> stack,
        Map<String, BigDecimal> requestCache);
  }
}
