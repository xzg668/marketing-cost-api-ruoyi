package com.sanhua.marketingcost.formula.normalize;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 变量别名索引 —— 启动时扫描 {@code lp_price_variable} 把 variable_code 与 aliases_json
 * 全部装入一棵字符级 Trie，供 {@code FormulaNormalizer} 做最长匹配替换。
 *
 * <p>为什么要 Trie 而不是简单 List 扫描：
 * <ul>
 *   <li>联动公式里一个字符串里可能同时出现多个变量（"下料重量*材料含税价格..."），
 *       需要一次扫描就能从某个位置向后找最长命中；</li>
 *   <li>别名可能共前缀（"1#Cu" vs "Cu"、"下料重量" vs "下料重"），
 *       简单包含匹配会选错短名，Trie 自然支持最长优先；</li>
 *   <li>O(n) 时间复杂度，与变量数无关。</li>
 * </ul>
 *
 * <p>冲突处理：同一别名串指向不同 variable_code 则启动时抛 {@link IllegalStateException}，
 * 避免生产期"铜"有时指 Cu 有时指 1#Cu。
 */
@Component
public class VariableAliasIndex {

  private static final Logger log = LoggerFactory.getLogger(VariableAliasIndex.class);

  private final PriceVariableMapper priceVariableMapper;

  /** JSON 解析器用于读 aliases_json；线程安全，可全局共享 */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** Trie 根节点，volatile 支持 {@link #refresh()} 热替换 */
  private volatile TrieNode root = new TrieNode();

  public VariableAliasIndex(PriceVariableMapper priceVariableMapper) {
    this.priceVariableMapper = priceVariableMapper;
  }

  /** Bean 初始化时全量加载一次 */
  @PostConstruct
  public void init() {
    refresh();
  }

  /**
   * 从数据库重新扫描变量并重建 Trie。
   *
   * <p>触发时机：Excel 导入影响因素后（别名可能改变），或管理端手工改 aliases_json 后。
   * 构建过程只读不写，出错（冲突别名）直接抛异常让调用方决定是否继续。
   */
  public synchronized void refresh() {
    List<PriceVariable> rows = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class).eq(PriceVariable::getStatus, "active"));
    TrieNode newRoot = new TrieNode();
    // 记录每个别名字符串已经绑定的 variable_code，用于冲突检测
    Map<String, String> aliasOwner = new HashMap<>();
    int aliasCount = 0;
    for (PriceVariable variable : rows) {
      String code = variable.getVariableCode();
      if (code == null || code.isBlank()) {
        continue;
      }
      code = code.trim();
      // variable_code 本身也是一个别名（Excel 公式可能直接写 Cu/Zn 等）
      insertAlias(newRoot, code, code, aliasOwner);
      aliasCount++;
      // 解析 aliases_json 里的中文/符号别名
      for (String alias : parseAliases(variable.getAliasesJson())) {
        insertAlias(newRoot, alias, code, aliasOwner);
        aliasCount++;
      }
    }
    this.root = newRoot;
    log.info("VariableAliasIndex 加载 {} 条变量，共 {} 个别名", rows.size(), aliasCount);
  }

  /**
   * 从 {@code text} 的 {@code offset} 位置开始尝试最长匹配。
   *
   * @return 命中 ? Optional.of({variableCode, matchedLength}) : Optional.empty()
   */
  public Optional<Match> match(String text, int offset) {
    if (text == null || offset < 0 || offset >= text.length()) {
      return Optional.empty();
    }
    TrieNode node = root;
    String bestCode = null;
    int bestLen = 0;
    for (int i = offset; i < text.length(); i++) {
      TrieNode next = node.children.get(text.charAt(i));
      if (next == null) {
        break;
      }
      node = next;
      if (node.variableCode != null) {
        // 当前位置有完整别名命中 —— 留存但继续扫，看是否有更长命中（最长优先）
        bestCode = node.variableCode;
        bestLen = i - offset + 1;
      }
    }
    return bestCode == null ? Optional.empty() : Optional.of(new Match(bestCode, bestLen));
  }

  /** 测试/调试用：返回全部别名 → code 映射（只读快照） */
  public Map<String, String> snapshot() {
    Map<String, String> out = new LinkedHashMap<>();
    collect(root, new StringBuilder(), out);
    return Collections.unmodifiableMap(out);
  }

  // ============================ 内部实现 ============================

  /** 递归 DFS 收集 Trie 里所有完整别名 */
  private void collect(TrieNode node, StringBuilder path, Map<String, String> out) {
    if (node.variableCode != null) {
      out.put(path.toString(), node.variableCode);
    }
    for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
      path.append(entry.getKey());
      collect(entry.getValue(), path, out);
      path.deleteCharAt(path.length() - 1);
    }
  }

  /** 把单个别名插入 Trie，并做冲突检测 */
  private void insertAlias(
      TrieNode root, String alias, String code, Map<String, String> aliasOwner) {
    if (alias == null || alias.isBlank()) {
      return;
    }
    alias = alias.trim();
    String existingOwner = aliasOwner.get(alias);
    if (existingOwner != null && !existingOwner.equals(code)) {
      String msg = String.format(
          "别名冲突：'%s' 同时映射到 [%s] 和 [%s]，请拆分或改名 (fail-fast)",
          alias, existingOwner, code);
      log.warn(msg);
      throw new IllegalStateException(msg);
    }
    aliasOwner.put(alias, code);
    TrieNode node = root;
    for (int i = 0; i < alias.length(); i++) {
      node = node.children.computeIfAbsent(alias.charAt(i), c -> new TrieNode());
    }
    // 同别名重复 insert 无副作用（冲突检测已前置）
    node.variableCode = code;
  }

  /** 解析 aliases_json 列为 List<String>；容错：null / 空 / 非法 JSON 都返回空列表 */
  private List<String> parseAliases(String aliasesJson) {
    if (aliasesJson == null || aliasesJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode node = objectMapper.readTree(aliasesJson);
      if (!node.isArray()) {
        return List.of();
      }
      List<String> list = new ArrayList<>(node.size());
      for (JsonNode element : node) {
        if (element.isTextual()) {
          list.add(element.asText());
        }
      }
      return list;
    } catch (Exception e) {
      log.warn("aliases_json 解析失败: {} err={}", aliasesJson, e.getMessage());
      return List.of();
    }
  }

  /** 匹配结果值对象 */
  public record Match(String variableCode, int length) {}

  /** Trie 节点：children 用 HashMap 足够，别名长度最多 10-20 个字符 */
  private static final class TrieNode {
    final Map<Character, TrieNode> children = new HashMap<>();
    /** 若当前位置是某别名的结尾，则保存绑定的 variable_code；否则 null */
    String variableCode;
  }
}
