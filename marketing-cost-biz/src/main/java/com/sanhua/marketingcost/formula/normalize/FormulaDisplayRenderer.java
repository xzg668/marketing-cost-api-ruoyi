package com.sanhua.marketingcost.formula.normalize;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 公式中文回显渲染器（Plan B T5）——
 * 把规范化后的 {@code formula_expr}（形如 {@code [Cu]*0.59+[Zn]*0.41}）反向映射成
 * 中文可读形态（{@code 电解铜*0.59+电解锌*0.41}），供 {@code formula_expr_cn} 列
 * 由后端派生而非手填。
 *
 * <p>为什么要派生：
 * <ul>
 *   <li>过去 {@code formula_expr_cn} 完全手填，和 {@code formula_expr} 两列无强一致，
 *       经常漂移 —— 公式改了中文没跟上，或反之。</li>
 *   <li>规范化之后 {@code formula_expr} 里只剩 {@code [variable_code]} 形态（T6 的
 *       normalize-on-save 强制），Renderer 查 {@code lp_price_variable.aliases_json}
 *       的第一项作为 display name，两列从此强一致。</li>
 * </ul>
 *
 * <p>反查链：{@code aliases_json[0]} &gt; {@code variable_name} &gt; 原始 {@code [code]} 保留。
 * 查不到的 token 原样保留是 <b>容错而非兜底</b>：
 * <ul>
 *   <li>容错：避免因一个 typo token 炸掉整行展示；</li>
 *   <li>非兜底：token 未命中会打 WARN，提示运维补齐 aliases，最终要治本。</li>
 * </ul>
 *
 * <p>缓存策略：和 {@link VariableAliasIndex} / {@code FactorVariableRegistryImpl}
 * 对齐 —— volatile 懒加载 {@code code → displayName} 映射，运维改完 aliases 后
 * 调 {@link #refresh()} 或重启服务即可生效。
 */
@Component
public class FormulaDisplayRenderer {

  private static final Logger log = LoggerFactory.getLogger(FormulaDisplayRenderer.class);

  /** 匹配 [xxx] 形式的变量占位符；xxx 不含 ']'。*/
  private static final Pattern VARIABLE_TOKEN = Pattern.compile("\\[([^\\]]+)\\]");

  private final PriceVariableMapper priceVariableMapper;
  private final RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** variable_code → display name 懒加载缓存；refresh() 热替换 */
  private volatile Map<String, String> codeToDisplay;

  public FormulaDisplayRenderer(
      PriceVariableMapper priceVariableMapper,
      RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry) {
    this.priceVariableMapper = priceVariableMapper;
    this.rowLocalPlaceholderRegistry = rowLocalPlaceholderRegistry;
  }

  /**
   * 把规范化公式渲染成中文回显形态。
   *
   * @param normalizedExpr 含 {@code [code]} token 的公式表达式
   * @return 同一表达式，但 token 被替换为 {@code aliases_json[0]} / {@code variable_name}；
   *         输入为 null/空白时原样返回
   */
  public String renderCn(String normalizedExpr) {
    if (normalizedExpr == null || normalizedExpr.isBlank()) {
      return normalizedExpr;
    }
    Map<String, String> map = loadOrGet();
    Matcher m = VARIABLE_TOKEN.matcher(normalizedExpr);
    StringBuilder out = new StringBuilder(normalizedExpr.length());
    while (m.find()) {
      String code = m.group(1);
      String display = map.get(code);
      if (display == null) {
        // 容错：未识别 token 原样保留 [code]，同时打 WARN 方便补 aliases
        log.warn("FormulaDisplayRenderer 未找到 variable_code='{}' 的 display name，原样保留", code);
        display = "[" + code + "]";
      }
      // 反斜杠 / $ 在 appendReplacement 里有特殊含义，必须转义
      m.appendReplacement(out, Matcher.quoteReplacement(display));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** 强制刷新缓存 —— Excel 导入或管理端改 aliases_json 后调用。 */
  public synchronized void refresh() {
    this.codeToDisplay = loadAll();
  }

  // ============================ 内部实现 ============================

  private Map<String, String> loadOrGet() {
    Map<String, String> cache = codeToDisplay;
    if (cache == null) {
      synchronized (this) {
        cache = codeToDisplay;
        if (cache == null) {
          cache = loadAll();
          codeToDisplay = cache;
        }
      }
    }
    return cache;
  }

  private Map<String, String> loadAll() {
    List<PriceVariable> rows = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class).eq(PriceVariable::getStatus, "active"));
    Map<String, String> map = new HashMap<>();
    for (PriceVariable row : rows) {
      String code = row.getVariableCode();
      if (code == null || code.isBlank()) {
        continue;
      }
      code = code.trim();
      String display = firstAlias(row.getAliasesJson());
      if (display == null || display.isBlank()) {
        // fallback 1：variable_name（多数变量会有）
        display = row.getVariableName();
      }
      if (display == null || display.isBlank()) {
        // fallback 2：variable_code 本身（保证渲染结果总是可读的英文/拼音）
        display = code;
      }
      map.put(code, display);
    }
    // V36：行局部占位符的显示名从 lp_row_local_placeholder 注册表取，而非硬编码。
    // 用 putIfAbsent 保证：如果运维同时把 __material 也登记到了 lp_price_variable
    // （不推荐，但合法），变量表的显示名优先 —— 避免两个源头同时配置出现矛盾。
    rowLocalPlaceholderRegistry.displayNames().forEach(map::putIfAbsent);
    log.info("FormulaDisplayRenderer 加载 {} 条 code→display 映射", map.size());
    return map;
  }

  /** 取 aliases_json 数组的第一项；解析失败 / 空数组返回 null */
  private String firstAlias(String aliasesJson) {
    if (aliasesJson == null || aliasesJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(aliasesJson);
      if (node.isArray() && node.size() > 0) {
        JsonNode first = node.get(0);
        if (first.isTextual()) {
          return first.asText();
        }
      }
    } catch (Exception e) {
      log.warn("aliases_json 解析失败: {} err={}", aliasesJson, e.getMessage());
    }
    return null;
  }
}
