package com.sanhua.marketingcost.formula.normalize;

import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 公式规范化管线 —— 把 Excel 原样公式转成 evaluator 可吃的形式：
 * <pre>
 *   原: 下料重量*（Cu*0.62/0.98+3.5元/Kg*1.13）/1000
 *   后: [blank_weight]*([Cu]*0.62/0.98+3.5*1.13)/1000
 * </pre>
 *
 * <p>五阶段（V34 起）：
 * <ol>
 *   <li>{@link #normalizeBrackets(String)} ——《中文括号/全角算符/空白》归一化到 ASCII；</li>
 *   <li>{@link #stripUnitAnnotations(String)} —— 剥离嵌入单位（{@code 元/Kg}、{@code 元/只} 等）；</li>
 *   <li>{@link #tagRowLocalTokens(String)} —— B 组 token（材料含税价格/废料含税价格等）→ {@code [__material]}/{@code [__scrap]}；</li>
 *   <li>{@link #tagVariables(String)} —— 借 {@link VariableAliasIndex} 做最长匹配替换为 {@code [code]}；</li>
 *   <li>{@link #cleanup(String)} —— 合并空白 + 校验括号平衡。</li>
 * </ol>
 *
 * <p>每阶段都是纯函数，便于独立单测。总入口 {@link #normalize(String)} 串联这五步。
 *
 * <p>为什么 B 组 token 独立成阶段、且放在 alias 替换之前：同一字面"材料含税价格"
 * 在不同联动行指向不同影响因素，不能用全局 VariableAliasIndex 消解；必须先替成
 * 行局部占位符 {@code [__material]}/{@code [__scrap]}，evaluator 在运行期按
 * {@code linked_item_id} 查 {@code lp_price_variable_binding} 反查 factor_code
 * 再递归求值。
 */
@Component
public class FormulaNormalizer {

  /**
   * 单位注释正则：至少一个中文字符 + {@code /} + 至少一个中文或 ASCII 字符。
   * 例：{@code 元/Kg}、{@code 元/KG}、{@code 元/只}、{@code 元/千克}。
   * 必须在"变量标签化"之前剥离，否则 {@code 元} 可能被当作变量字符。
   */
  private static final Pattern UNIT_PATTERN =
      Pattern.compile("[\\u4e00-\\u9fa5]+/[A-Za-z\\u4e00-\\u9fa5]+");

  private final VariableAliasIndex aliasIndex;
  /**
   * V36：行局部占位符配置注册表 —— 取代过去硬编码的 {@code ROW_LOCAL_TOKEN_MAP}，
   * 由 {@code lp_row_local_placeholder} 表驱动。扫描时从 registry 拿
   * {@code code → tokenNames[]} 翻转成 {@code tokenName → code} 的视图。
   */
  private final RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry;

  @Autowired
  public FormulaNormalizer(
      VariableAliasIndex aliasIndex,
      RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry) {
    this.aliasIndex = aliasIndex;
    this.rowLocalPlaceholderRegistry = rowLocalPlaceholderRegistry;
  }

  /**
   * 构造 {@code tokenName → code}（中文 token → 占位符）正向扫描表。
   *
   * <p>registry 里原始结构是 {@code code → tokenNames[]}（一 code 多别名），
   * 扫描时要反向用。此处每次 {@code normalize} 调用时重建；registry 自身有
   * volatile 缓存，实际开销极低。
   */
  private Map<String, String> buildTokenToCodeMap() {
    LinkedHashMap<String, String> m = new LinkedHashMap<>();
    rowLocalPlaceholderRegistry.tokenNames().forEach((code, tokens) -> {
      for (String token : tokens) {
        if (token != null && !token.isBlank()) {
          m.put(token, code);
        }
      }
    });
    return m;
  }

  /**
   * 规范化总入口 —— 按五阶段管线处理。
   *
   * @param raw Excel 原样公式（可含中文括号、中文变量、单位注释、B 组 token）
   * @return 规范化后的表达式；{@code null/空} 直接返回 {@code ""}
   * @throws FormulaSyntaxException 括号不平衡或其它结构性错误
   */
  public String normalize(String raw) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    String s = normalizeBrackets(raw);
    s = stripUnitAnnotations(s);
    s = tagRowLocalTokens(s);
    s = tagVariables(s);
    return cleanup(s);
  }

  // ============================ 阶段 1 ============================

  /**
   * 括号 &amp; 全角算符 &amp; 空白归一化：
   * <ul>
   *   <li>{@code （} → {@code (}；{@code ）} → {@code )}</li>
   *   <li>{@code 【} → {@code [}；{@code 】} → {@code ]}（Excel 偶见方头括号作注释）</li>
   *   <li>{@code ＊} → {@code *}；{@code ／} → {@code /}；{@code －} → {@code -}；{@code ＋} → {@code +}</li>
   *   <li>{@code ，} → {@code ,}</li>
   *   <li>全角空格 U+3000 / 不间断空格 U+00A0 → ASCII 空格</li>
   * </ul>
   */
  String normalizeBrackets(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '（') {
        sb.append('(');
      } else if (c == '）') {
        sb.append(')');
      } else if (c == '【') {
        sb.append('[');
      } else if (c == '】') {
        sb.append(']');
      } else if (c == '＊') {
        sb.append('*');
      } else if (c == '／') {
        sb.append('/');
      } else if (c == '－') {
        sb.append('-');
      } else if (c == '＋') {
        sb.append('+');
      } else if (c == '，') {
        sb.append(',');
      } else if (c == '　' || c == ' ') {
        // 全角空格 / 不间断空格
        sb.append(' ');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // ============================ 阶段 2 ============================

  /**
   * 单位注释剥离：把形如 {@code 元/Kg}、{@code 元/只} 的单位片段去掉，保留数值部分。
   * 正则匹配 "一段中文 + 斜杠 + 一段字符"，该片段整段替换为空。
   *
   * <p>示例：{@code 3.5元/Kg*1.13} → {@code 3.5*1.13}。
   */
  String stripUnitAnnotations(String s) {
    return UNIT_PATTERN.matcher(s).replaceAll("");
  }

  // ============================ 阶段 2.5 ============================

  /**
   * B 组 token（行局部）→ 占位符替换。
   *
   * <p>扫描 {@link #ROW_LOCAL_TOKEN_MAP}，把"材料含税价格/废料含税价格/材料价格/废料价格"
   * 替换成 {@code [__material]}/{@code [__scrap]}。这两个占位符不对应全局变量；
   * evaluator 按当前联动行 id 反查 {@code lp_price_variable_binding} 拿到实际 factor_code。
   *
   * <p>顺序：在 {@link #stripUnitAnnotations} 之后（避免"废料"被误剥单位）、
   * {@link #tagVariables} 之前（避免被 VariableAliasIndex 当全局别名吃掉）。
   */
  String tagRowLocalTokens(String s) {
    Map<String, String> tokenToCode = buildTokenToCodeMap();
    if (tokenToCode.isEmpty()) {
      // registry 里一条占位符都没配（全新安装？）——直接返回，走后续阶段
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      // 跳过已经包裹好的 [xxx]（如 [blank_weight]），避免嵌套误伤
      if (c == '[') {
        int end = s.indexOf(']', i);
        if (end > i) {
          sb.append(s, i, end + 1);
          i = end + 1;
          continue;
        }
      }
      // 最长优先：遍历所有 token，取命中且字符数最长者
      String bestToken = null;
      int bestLen = 0;
      for (String token : tokenToCode.keySet()) {
        int tlen = token.length();
        if (tlen > bestLen && i + tlen <= s.length() && s.regionMatches(i, token, 0, tlen)) {
          bestToken = token;
          bestLen = tlen;
        }
      }
      if (bestToken != null) {
        sb.append('[').append(tokenToCode.get(bestToken)).append(']');
        i += bestLen;
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  // ============================ 阶段 3 ============================

  /**
   * 变量标签化：从左到右扫描，遇到已注册别名的最长前缀即替换为 {@code [code]}；
   * 其余字符原样保留，交给 evaluator 处理（数值、算符、括号）。
   */
  String tagVariables(String s) {
    StringBuilder sb = new StringBuilder(s.length() * 2);
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      // 跳过已经包裹好的 [xxx]（可能是递归调用的产物，或上一阶段替换的行局部占位符）
      if (c == '[') {
        int end = s.indexOf(']', i);
        if (end > i) {
          sb.append(s, i, end + 1);
          i = end + 1;
          continue;
        }
      }
      var m = aliasIndex.match(s, i);
      if (m.isPresent()) {
        sb.append('[').append(m.get().variableCode()).append(']');
        i += m.get().length();
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  // ============================ 阶段 4 ============================

  /**
   * 清理阶段：
   * <ul>
   *   <li>压缩连续空白为单个空格</li>
   *   <li>校验括号平衡；不平衡抛 {@link FormulaSyntaxException}</li>
   *   <li>Plan B 严格写入口径：扫描非 {@code [...]} 区段内的残留 CJK 字符 —— 有则说明存在
   *       未注册别名的中文变量，抛 {@link FormulaSyntaxException}，不再静默放行</li>
   * </ul>
   */
  String cleanup(String s) {
    String collapsed = s.replaceAll("\\s+", " ").trim();
    int balance = 0;
    boolean inBracket = false;
    for (int i = 0; i < collapsed.length(); i++) {
      char c = collapsed.charAt(i);
      if (c == '[') {
        inBracket = true;
      } else if (c == ']') {
        inBracket = false;
      } else if (c == '(') {
        balance++;
      } else if (c == ')') {
        balance--;
        if (balance < 0) {
          throw new FormulaSyntaxException(
              "右括号比左括号多，位置=" + i + "，原文=" + collapsed);
        }
      } else if (!inBracket && isCjk(c)) {
        // 非 [code] 区段出现 CJK，即没能在 tagVariables 阶段被替换 → 别名未注册
        throw new FormulaSyntaxException(
            "公式包含未识别的中文 token（位置=" + i + "，字符=" + c + "）：" + collapsed);
      }
    }
    if (balance != 0) {
      throw new FormulaSyntaxException("括号不平衡（差值=" + balance + "），原文=" + collapsed);
    }
    return collapsed;
  }

  /** CJK 统一表意区段（U+4E00–U+9FA5）判断。 */
  private static boolean isCjk(char c) {
    return c >= '一' && c <= '龥';
  }
}
