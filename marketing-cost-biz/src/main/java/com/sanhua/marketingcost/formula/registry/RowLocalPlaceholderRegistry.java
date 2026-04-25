package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.RowLocalPlaceholder;
import com.sanhua.marketingcost.mapper.RowLocalPlaceholderMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 行局部占位符注册表 —— {@code lp_row_local_placeholder} 的只读视图，对外提供
 * 两个场景化的 {@link Map}：
 * <ul>
 *   <li>{@link #displayNames()}  —— {@code code → displayName}，
 *       供 {@link com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer}
 *       在公式中文回显时使用</li>
 *   <li>{@link #tokenNames()}    —— {@code code → tokenNames[]}，
 *       供 {@link FactorVariableRegistryImpl} 在扫描
 *       {@code lp_price_variable_binding.token_name} 时使用</li>
 * </ul>
 *
 * <p>缓存策略：{@code volatile} 双检锁懒加载，和 {@code variableCache} /
 * {@code FormulaDisplayRenderer.codeToDisplay} 同一模式。
 * 运维通过 {@link #invalidate()} 主动失效；正常 CRUD 路径由 Controller/Service 调它。
 *
 * <p>为什么独立成一个 registry 而不是散在各调用方：
 * <ul>
 *   <li>单一事实源 —— 新增占位符只改一张表，所有消费者立刻一致</li>
 *   <li>集中处理 {@code token_names_json} 的 JSON 解析、status 过滤、
 *       异常行容错，避免每个消费者重复做相同逻辑</li>
 * </ul>
 */
@Component
public class RowLocalPlaceholderRegistry {

  private static final Logger log = LoggerFactory.getLogger(RowLocalPlaceholderRegistry.class);

  private static final TypeReference<List<String>> TOKEN_NAMES_TYPE = new TypeReference<>() {};

  private final RowLocalPlaceholderMapper mapper;
  private final ObjectMapper objectMapper;

  /** 加载一次的快照；失效后下一次访问重建 */
  private volatile Snapshot snapshot;

  public RowLocalPlaceholderRegistry(
      RowLocalPlaceholderMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /** {@code code → displayName}；LinkedHashMap 保持 {@code sort_order} 序 */
  public Map<String, String> displayNames() {
    return loadOrGet().displayNames;
  }

  /** {@code code → tokenNames[]}；供 binding 扫描使用 */
  public Map<String, List<String>> tokenNames() {
    return loadOrGet().tokenNames;
  }

  /** 是否是已登记的行局部占位符 code（比 `code.startsWith("__")` 更严谨） */
  public boolean isKnown(String code) {
    return code != null && loadOrGet().tokenNames.containsKey(code);
  }

  /** 清空缓存 —— Controller 写后调用，下一次访问重建快照 */
  public void invalidate() {
    this.snapshot = null;
  }

  // ============================ 内部实现 ============================

  private Snapshot loadOrGet() {
    Snapshot s = snapshot;
    if (s == null) {
      synchronized (this) {
        s = snapshot;
        if (s == null) {
          s = buildSnapshot();
          snapshot = s;
        }
      }
    }
    return s;
  }

  private Snapshot buildSnapshot() {
    List<RowLocalPlaceholder> rows = mapper.selectList(
        Wrappers.lambdaQuery(RowLocalPlaceholder.class)
            .eq(RowLocalPlaceholder::getStatus, "active")
            .orderByAsc(RowLocalPlaceholder::getSortOrder)
            .orderByAsc(RowLocalPlaceholder::getId));
    Map<String, String> display = new LinkedHashMap<>();
    Map<String, List<String>> tokens = new LinkedHashMap<>();
    for (RowLocalPlaceholder row : rows) {
      String code = row.getCode();
      if (code == null || code.isBlank()) {
        log.warn("lp_row_local_placeholder id={} code 为空，跳过", row.getId());
        continue;
      }
      code = code.trim();
      if (row.getDisplayName() != null && !row.getDisplayName().isBlank()) {
        display.put(code, row.getDisplayName().trim());
      } else {
        // fallback：显示名空则回退到 code 自身（保证渲染不炸）
        display.put(code, code);
      }
      List<String> tokenNames = parseTokenNames(row);
      if (!tokenNames.isEmpty()) {
        tokens.put(code, tokenNames);
      } else {
        log.warn("占位符 {} 的 token_names_json 为空 —— binding 扫描将无法命中该 code",
            code);
      }
    }
    log.info("RowLocalPlaceholderRegistry 加载 {} 条占位符（display={}, tokens={}）",
        rows.size(), display.size(), tokens.size());
    return new Snapshot(display, tokens);
  }

  private List<String> parseTokenNames(RowLocalPlaceholder row) {
    String json = row.getTokenNamesJson();
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> parsed = objectMapper.readValue(json, TOKEN_NAMES_TYPE);
      // 去空、去前后空白；List.copyOf 保证不可变
      return parsed.stream()
          .filter(s -> s != null && !s.isBlank())
          .map(String::trim)
          .toList();
    } catch (Exception e) {
      log.warn("占位符 {} 的 token_names_json 解析失败: {} —— {}",
          row.getCode(), json, e.getMessage());
      return List.of();
    }
  }

  /** 快照容器 —— 两个 map 一起重建，保证一致性 */
  private record Snapshot(
      Map<String, String> displayNames, Map<String, List<String>> tokenNames) {}
}
