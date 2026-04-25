package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link FactorVariableRegistry} 默认实现 ——
 * Plan B 改造：按 {@code resolver_kind} 分派，参数一律来自 {@code resolver_params} JSON。
 *
 * <p>与旧版（按 {@code factor_type} + 一二代列散装读取）相比的关键差异：
 * <ol>
 *   <li>分发键从 {@code factor_type}（兼做 UI 分组 + 解析路由）拆分：
 *       {@code factor_type} 只做前端 catalog 分组；后端解析严格走 {@code resolver_kind}。</li>
 *   <li>FINANCE 分支不再把 {@code variable_name} 当 short_name 查、不再"月份回退"，
 *       改走 {@link FinanceBasePriceQuery} 的四键精确匹配
 *       （factorCode|shortName + priceSource + pricingMonth + bu），
 *       与 legacy 计算路径口径完全一致 —— 修复 /items/{id}/trace 返回 0 的根因。</li>
 *   <li>ENTITY/DERIVED/FORMULA/CONST 四种 kind 的参数通通从同一个
 *       {@code resolver_params} JSON 读，不再读 {@code context_binding_json} /
 *       {@code formula_expr} / {@code default_value} 三套散列。</li>
 * </ol>
 *
 * <p>环检：{@code visiting} LinkedHashSet 作为递归参数传递，重复加入即环，
 * 抛 {@link CircularFormulaException} 并带完整环路径。
 *
 * <p>变量元数据缓存：volatile 懒加载，运维改完 {@code resolver_params} 后重启服务即生效。
 */
@Component
public class FactorVariableRegistryImpl implements FactorVariableRegistry {

  private static final Logger log = LoggerFactory.getLogger(FactorVariableRegistryImpl.class);

  /** resolver_kind 常量 —— 与 V31 回填脚本保持一致 */
  private static final String KIND_FINANCE = "FINANCE";
  private static final String KIND_ENTITY = "ENTITY";
  private static final String KIND_DERIVED = "DERIVED";
  private static final String KIND_FORMULA = "FORMULA";
  private static final String KIND_CONST = "CONST";

  private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

  private final PriceVariableMapper priceVariableMapper;
  private final FinanceBasePriceQuery financeBasePriceQuery;
  private final ObjectMapper objectMapper;

  /**
   * V36 行局部占位符注册表 —— 取代过去的 {@code ROW_LOCAL_TOKEN_NAMES} 静态表，
   * 改由 {@code lp_row_local_placeholder} DB 表驱动，新增占位符无需重新发版。
   */
  private final RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry;

  /**
   * V34 行局部绑定 mapper —— 可选注入（独立单测场景可不装配）。
   * 如果公式里用了 {@code [__material]} 但该 mapper 为 null，
   * {@link #resolveRowLocalToken} 会抛 {@link IllegalStateException}。
   */
  private PriceVariableBindingMapper priceVariableBindingMapper;

  /**
   * linked_item_id → (token_name → 当前生效 binding) 二级缓存。
   * 进程内 ConcurrentHashMap，写路径（Service 层）通过
   * {@link #invalidateBinding(Long)} 主动失效。和 {@link #variableCache} 同风格——
   * 重启兜底 + 写触发失效，不加 TTL。
   */
  private final Map<Long, Map<String, PriceVariableBinding>> bindingCache =
      new ConcurrentHashMap<>();

  /** PART_CONTEXT DERIVED 委托解析器，Spring 装配 DerivedResolver bean 后生效 */
  private DerivedContextResolver derivedContextResolver;

  /** variableCode → PriceVariable 懒加载缓存 */
  private volatile Map<String, PriceVariable> variableCache;

  public FactorVariableRegistryImpl(
      PriceVariableMapper priceVariableMapper,
      FinanceBasePriceQuery financeBasePriceQuery,
      ObjectMapper objectMapper,
      RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry) {
    this.priceVariableMapper = priceVariableMapper;
    this.financeBasePriceQuery = financeBasePriceQuery;
    this.objectMapper = objectMapper;
    this.rowLocalPlaceholderRegistry = rowLocalPlaceholderRegistry;
  }

  /** 延迟注入 —— T10 的 DerivedResolver 实现此接口后自动装配 */
  @Autowired(required = false)
  public void setDerivedContextResolver(DerivedContextResolver derivedContextResolver) {
    this.derivedContextResolver = derivedContextResolver;
  }

  /**
   * V34 延迟注入 —— 独立单测场景（FactorVariableRegistryImplTest）可不装配；
   * 生产运行时 Spring 会自动注入。
   */
  @Autowired(required = false)
  public void setPriceVariableBindingMapper(PriceVariableBindingMapper mapper) {
    this.priceVariableBindingMapper = mapper;
  }

  @Override
  public Optional<BigDecimal> resolve(String variableCode, VariableContext ctx) {
    if (variableCode == null || variableCode.isBlank()) {
      return Optional.empty();
    }
    return resolveInternal(
        variableCode.trim(), ctx, new LinkedHashSet<>(), new HashMap<>());
  }

  /**
   * 递归解析入口 —— FormulaRef / DerivedResolver 会在子变量上重入本方法
   * 以继承 visiting 栈与缓存，保证跨层环路 A→B→C→A 也能被捕获。
   */
  Optional<BigDecimal> resolveInternal(
      String code,
      VariableContext ctx,
      LinkedHashSet<String> visiting,
      Map<String, Optional<BigDecimal>> cache) {
    // 上下文 overrides 优先级最高（测试覆盖与手工临时兜底都靠它）
    if (ctx != null && ctx.getOverrides().containsKey(code)) {
      return Optional.ofNullable(ctx.getOverrides().get(code));
    }
    if (cache.containsKey(code)) {
      return cache.get(code);
    }
    // V34：行局部占位符 [__material] / [__scrap] / ... 优先分支——
    // 这些 code 不存在于 lp_price_variable，按 linked_item_id 查 lp_price_variable_binding
    // 拿到 factor_code 后再递归走主路径。
    // V36：占位符列表改由 lp_row_local_placeholder DB 表驱动，支持运维自助扩展。
    if (rowLocalPlaceholderRegistry.isKnown(code)) {
      Optional<BigDecimal> resolved = resolveRowLocalToken(code, ctx, visiting, cache);
      return putAndReturn(cache, code, resolved);
    }
    // 环检：当前 code 加入 visiting，重复即抛
    if (!visiting.add(code)) {
      List<String> path = new ArrayList<>(visiting);
      int start = path.indexOf(code);
      List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
      cycle.add(code);
      throw new CircularFormulaException(cycle);
    }
    try {
      PriceVariable variable = lookup(code);
      if (variable == null) {
        log.debug("FactorVariableRegistry 未找到变量定义: code={}", code);
        return putAndReturn(cache, code, Optional.empty());
      }
      String kind = variable.getResolverKind();
      if (kind == null || kind.isBlank()) {
        // resolver_kind 为 null 的合法场景：Al/Sn/Cn 等"数据未配置"的 FINANCE
        log.warn("变量 {} 未声明 resolver_kind（数据未配置），返回 empty", code);
        return putAndReturn(cache, code, Optional.empty());
      }
      Map<String, Object> params = parseParams(variable);
      Optional<BigDecimal> result = dispatch(
          variable, kind.trim().toUpperCase(), params, ctx, visiting, cache);
      return putAndReturn(cache, code, result);
    } finally {
      visiting.remove(code);
    }
  }

  /** 按 resolver_kind 路由到对应分支；未知 kind 返回 empty + WARN，不抛 */
  private Optional<BigDecimal> dispatch(
      PriceVariable variable,
      String kind,
      Map<String, Object> params,
      VariableContext ctx,
      LinkedHashSet<String> visiting,
      Map<String, Optional<BigDecimal>> cache) {
    switch (kind) {
      case KIND_FINANCE:
        return resolveFinance(variable, params, ctx);
      case KIND_ENTITY:
        return resolveEntity(variable, params, ctx);
      case KIND_DERIVED:
        return resolveDerived(variable, params, ctx, visiting, cache);
      case KIND_FORMULA:
        return resolveFormula(variable, params, ctx, visiting, cache);
      case KIND_CONST:
        return resolveConst(variable, params);
      default:
        log.warn("变量 {} 的 resolver_kind={} 未知，返回 empty",
            variable.getVariableCode(), kind);
        return Optional.empty();
    }
  }

  /**
   * FINANCE：四键精确查 {@code lp_finance_base_price}。
   *
   * <p>params 契约：
   * <pre>{"factorCode":"Cu","priceSource":"平均价","buScoped":true}
   *      或 {"shortName":"美国柜装黄铜","priceSource":"平均价","buScoped":true}</pre>
   */
  Optional<BigDecimal> resolveFinance(
      PriceVariable variable, Map<String, Object> params, VariableContext ctx) {
    if (params.isEmpty()) {
      log.warn("FINANCE 变量 {} 缺 resolver_params", variable.getVariableCode());
      return Optional.empty();
    }
    String factorCode = asString(params.get("factorCode"));
    String shortName = asString(params.get("shortName"));
    String priceSource = asString(params.get("priceSource"));
    boolean buScoped = asBoolean(params.get("buScoped"), false);
    String pricingMonth = ctx == null ? null : ctx.getPricingMonth();
    String bu = BusinessUnitContext.getCurrentBusinessUnitType();

    Optional<FinanceBasePrice> row = financeBasePriceQuery.queryLatestBasePrice(
        factorCode, shortName, priceSource, buScoped, pricingMonth, bu,
        variable.getVariableCode());
    return row.map(FinanceBasePrice::getPrice);
  }

  /**
   * ENTITY：按 {@code entity + field + unitScale} 反射读 {@link PriceLinkedItem} 字段。
   *
   * <p>params 契约：
   * <pre>{"entity":"linkedItem","field":"blankWeight","unitScale":0.001}</pre>
   */
  Optional<BigDecimal> resolveEntity(
      PriceVariable variable, Map<String, Object> params, VariableContext ctx) {
    String entity = asString(params.get("entity"));
    String field = asString(params.get("field"));
    if (entity == null || field == null) {
      log.warn("ENTITY 变量 {} 缺 entity/field 字段", variable.getVariableCode());
      return Optional.empty();
    }
    // 当前阶段只支持 linkedItem；后续扩展到 calcItem 再加分支
    if (!"linkedItem".equalsIgnoreCase(entity)) {
      log.warn("ENTITY 变量 {} 暂只支持 entity=linkedItem，实际={}",
          variable.getVariableCode(), entity);
      return Optional.empty();
    }
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null) {
      return Optional.empty();
    }
    BigDecimal raw = readDecimalByGetter(item, field);
    if (raw == null) {
      return Optional.empty();
    }
    BigDecimal scale = asBigDecimal(params.get("unitScale"));
    if (scale == null) {
      return Optional.of(raw);
    }
    return Optional.of(raw.multiply(scale));
  }

  /**
   * DERIVED：委托给 {@link DerivedContextResolver}，params 原样透传。
   *
   * <p>params 契约（4 种 strategy 之一）：
   * <pre>{"strategy":"MAIN_MATERIAL_FINANCE"}
   *      {"strategy":"SCRAP_REF"}
   *      {"strategy":"FORMULA_REF","formulaRef":"[Cu]*0.59+[Zn]*0.41"}
   *      {"strategy":"FINANCE_FACTOR","factorCode":"美国柜装黄铜"}</pre>
   */
  Optional<BigDecimal> resolveDerived(
      PriceVariable variable,
      Map<String, Object> params,
      VariableContext ctx,
      LinkedHashSet<String> visiting,
      Map<String, Optional<BigDecimal>> cache) {
    if (derivedContextResolver == null) {
      log.info("DERIVED 变量 {} 但 DerivedResolver 未装配（独立单测场景？），返回 empty",
          variable.getVariableCode());
      return Optional.empty();
    }
    // 子变量递归：FORMULA_REF 策略里的 [Cu][Zn] 等要走完整 registry 解析路径
    DerivedContextResolver.SubResolver sub =
        code -> resolveInternal(code, ctx, visiting, cache);
    return derivedContextResolver.resolve(variable, ctx, params, sub);
  }

  /**
   * FORMULA：对 {@code params.expr} 中的 {@code [变量]} 递归求值并代入表达式计算。
   */
  Optional<BigDecimal> resolveFormula(
      PriceVariable variable,
      Map<String, Object> params,
      VariableContext ctx,
      LinkedHashSet<String> visiting,
      Map<String, Optional<BigDecimal>> cache) {
    String expr = asString(params.get("expr"));
    if (expr == null || expr.isBlank()) {
      log.warn("FORMULA 变量 {} resolver_params 缺 expr", variable.getVariableCode());
      return Optional.empty();
    }
    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables(expr);
    Map<String, BigDecimal> values = new HashMap<>();
    for (String token : tokens) {
      // 子变量递归：继承 visiting 栈 + cache 以保证多层环 A→B→C→A 能被捕获
      Optional<BigDecimal> sub = resolveInternal(token, ctx, visiting, cache);
      values.put(token, sub.orElse(BigDecimal.ZERO));
    }
    BigDecimal result = ExpressionEvaluator.evaluate(expr, values);
    return Optional.ofNullable(result);
  }

  /** CONST：直接返回 {@code params.value} 的 BigDecimal 值 */
  Optional<BigDecimal> resolveConst(PriceVariable variable, Map<String, Object> params) {
    BigDecimal v = asBigDecimal(params.get("value"));
    if (v == null) {
      log.warn("CONST 变量 {} resolver_params 缺 value", variable.getVariableCode());
    }
    return Optional.ofNullable(v);
  }

  /** 把 resolver_params JSON 串解析成 Map；失败 / 空 → 空 map（不抛，由各 resolver 自判） */
  private Map<String, Object> parseParams(PriceVariable variable) {
    String json = variable.getResolverParams();
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, PARAMS_TYPE);
    } catch (Exception e) {
      log.warn("变量 {} resolver_params JSON 解析失败: {} —— {}",
          variable.getVariableCode(), json, e.getMessage());
      return Map.of();
    }
  }

  /** 变量元数据懒加载 —— 首次访问拉全量 status=active */
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
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getStatus, "active"));
    Map<String, PriceVariable> map = new HashMap<>();
    for (PriceVariable row : rows) {
      if (row.getVariableCode() != null) {
        map.put(row.getVariableCode().trim(), row);
      }
    }
    log.info("FactorVariableRegistry 加载 {} 个变量定义", map.size());
    return map;
  }

  /**
   * 清空缓存 —— 下一次 {@link #lookup(String)} 会重新从 DB 拉全量。
   *
   * <p>使用时机：
   * <ul>
   *   <li>单测清场：同一 JVM 多次跑用例需手动失效缓存</li>
   *   <li>运维 CRUD：PriceVariableService 写后调用本方法；否则新增/停用的变量要等到下次重启才生效</li>
   * </ul>
   */
  public void invalidate() {
    this.variableCache = null;
    this.bindingCache.clear();
  }

  /**
   * 按 linked_item_id 清空行局部绑定缓存 —— PriceVariableBindingService 写后调用。
   *
   * @param linkedItemId null 时清空全部
   */
  public void invalidateBinding(Long linkedItemId) {
    if (linkedItemId == null) {
      this.bindingCache.clear();
    } else {
      this.bindingCache.remove(linkedItemId);
    }
  }

  // ============================ V34 行局部绑定解析 ============================

  /**
   * 行局部 token → binding 查询 → 递归解析其 {@code factor_code}。
   *
   * <p>前置：{@code code} 形如 {@code __material} / {@code __scrap}（不含方括号）。
   *
   * <p>失败模式：
   * <ul>
   *   <li>{@code ctx.linkedItem} 为 null → IllegalStateException（编排错误，不该发生）</li>
   *   <li>mapper 未注入但有 {@code __xxx} → IllegalStateException</li>
   *   <li>DB 里查不到当前生效 binding → {@link UnboundRowLocalTokenException}</li>
   *   <li>binding 指向的 factor_code 在 lp_price_variable 查不到 → 子递归返回 empty（WARN）</li>
   * </ul>
   */
  Optional<BigDecimal> resolveRowLocalToken(
      String code,
      VariableContext ctx,
      LinkedHashSet<String> visiting,
      Map<String, Optional<BigDecimal>> cache) {
    PriceLinkedItem item = ctx == null ? null : ctx.getLinkedItem();
    if (item == null || item.getId() == null) {
      throw new IllegalStateException(
          "解析行局部 token [" + code + "] 需要 ctx.linkedItem.id，但未提供 —— "
              + "检查调用方是否在 VariableContext 里塞了联动行实体");
    }
    if (priceVariableBindingMapper == null) {
      throw new IllegalStateException(
          "公式使用了行局部 token [" + code + "] 但 PriceVariableBindingMapper 未注入 —— "
              + "检查 Spring 装配或单测 fixture");
    }
    Long itemId = item.getId();
    Map<String, PriceVariableBinding> bindings = bindingCache.computeIfAbsent(
        itemId, this::loadBindings);
    List<String> candidateTokenNames = rowLocalPlaceholderRegistry.tokenNames().get(code);
    if (candidateTokenNames == null || candidateTokenNames.isEmpty()) {
      // 理论上不会走到：isKnown(code) 已保证 code 在注册表里，且有 tokenNames
      throw new IllegalStateException(
          "行局部占位符 [" + code + "] 在 lp_row_local_placeholder 未配置 token_names —— "
              + "请在占位符表中填写 token_names_json");
    }
    PriceVariableBinding hit = null;
    for (String token : candidateTokenNames) {
      PriceVariableBinding b = bindings.get(token);
      if (b != null) {
        hit = b;
        break;
      }
    }
    if (hit == null) {
      throw new UnboundRowLocalTokenException(
          itemId, code,
          String.format(
              "linked_item_id=%d 的行局部 token [%s]（候选 token_name=%s）在 "
                  + "lp_price_variable_binding 里没有当前生效的绑定——请在"
                  + "价格变量维护页 -> 变量绑定 tab 配置，或提交供管部 CSV。",
              itemId, code, candidateTokenNames));
    }
    String factorCode = hit.getFactorCode();
    if (factorCode == null || factorCode.isBlank()) {
      throw new UnboundRowLocalTokenException(
          itemId, code,
          String.format(
              "linked_item_id=%d 的 binding id=%d 未配置 factor_code —— "
                  + "binding 数据整合性异常，请联系运维核查。",
              itemId, hit.getId()));
    }
    // binding 的 factor_code 必须在 lp_price_variable 已登记；否则属于供管部映射与
    // 变量表漂移（如变量被停用但 binding 未跟随更新），fail-fast 暴露而不是静默 0。
    if (lookup(factorCode) == null) {
      throw new UnboundRowLocalTokenException(
          itemId, code,
          String.format(
              "linked_item_id=%d 的 binding[%s] 指向 factor_code=%s，"
                  + "但 lp_price_variable 里未登记该变量 —— "
                  + "请检查变量表或修正 binding。",
              itemId, code, factorCode));
    }
    // 递归：factor_code 走主路径（可能是 FINANCE/DERIVED/FORMULA/CONST 任一种）
    return resolveInternal(factorCode, ctx, visiting, cache);
  }

  /**
   * 从 DB 加载某联动行的全部当前生效 binding，键为 token_name。
   * 同一 (linked_item_id, token_name) 理论上 UNIQUE（V34 表约束），故 put 安全。
   */
  private Map<String, PriceVariableBinding> loadBindings(Long linkedItemId) {
    List<PriceVariableBinding> rows =
        priceVariableBindingMapper.findCurrentByLinkedItemId(linkedItemId);
    Map<String, PriceVariableBinding> map = new HashMap<>();
    for (PriceVariableBinding b : rows) {
      if (b.getTokenName() != null) {
        map.put(b.getTokenName(), b);
      }
    }
    log.debug("加载 linked_item_id={} 的 {} 条行局部 binding", linkedItemId, map.size());
    return map;
  }

  // ============================ 私有工具 ============================

  /** 把结果写入 cache 并返回 —— 减少每个 return 的样板代码 */
  private static Optional<BigDecimal> putAndReturn(
      Map<String, Optional<BigDecimal>> cache, String code, Optional<BigDecimal> value) {
    cache.put(code, value);
    return value;
  }

  /** 反射读 {@code item.get<Field>()} 的 BigDecimal 值；读失败统一返回 null */
  private static BigDecimal readDecimalByGetter(Object target, String field) {
    if (target == null || field == null || field.isEmpty()) {
      return null;
    }
    try {
      String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
      var method = target.getClass().getMethod(getter);
      Object value = method.invoke(target);
      if (value == null) {
        return null;
      }
      if (value instanceof BigDecimal bd) {
        return bd;
      }
      if (value instanceof Number n) {
        return new BigDecimal(n.toString());
      }
      return new BigDecimal(value.toString());
    } catch (ReflectiveOperationException | NumberFormatException e) {
      log.debug("反射读字段 {} 失败: {}", field, e.getMessage());
      return null;
    }
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static BigDecimal asBigDecimal(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof BigDecimal bd) {
      return bd;
    }
    if (o instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return new BigDecimal(o.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean asBoolean(Object o, boolean def) {
    if (o == null) {
      return def;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(o.toString());
  }

  /**
   * PART_CONTEXT DERIVED 派生策略解析抽象 —— T10 新增实现。
   *
   * <p>保留接口与旧版完全一致：实现类（{@link DerivedResolver}）读 params map 里的
   * {@code strategy} / {@code formulaRef} / {@code factorCode} 等键，和 V31 回填的
   * {@code resolver_params} JSON 结构完全对齐，所以改造无需动实现类。
   */
  public interface DerivedContextResolver {
    /**
     * @param variable 变量元数据
     * @param ctx 请求上下文（含 linkedItem / oaForm）
     * @param binding 解析后的参数 Map —— Plan B 之后来自 {@code resolver_params}
     * @param subResolver 子变量递归解析回调；派生公式里的 [Cu][Zn] 等子变量靠它回到
     *     registry 主路径，继承 visiting 栈和缓存
     */
    Optional<BigDecimal> resolve(
        PriceVariable variable, VariableContext ctx, Map<String, Object> binding,
        SubResolver subResolver);

    /** 子变量解析器 —— registry 传入，封装 resolveInternal(code, ctx, visiting, cache) */
    @FunctionalInterface
    interface SubResolver {
      Optional<BigDecimal> resolve(String variableCode);
    }
  }
}
