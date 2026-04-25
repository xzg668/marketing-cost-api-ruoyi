package com.sanhua.marketingcost.formula.normalize;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 公式"结构校验器" —— 只做语法级 & code 白名单校验，不做求值。
 *
 * <p>为什么在 {@link FormulaNormalizer} 之外再加一层：
 * Normalizer 只负责"把原文归一化成 [code] 形式"，它拦的是「含未识别中文」「括号不平衡」；
 * 不拦以下结构错（这是列表页红字标注漏掉的根因）：
 * <ul>
 *   <li>相邻 value 缺运算符：{@code [a][b]} / {@code [a](b)} / {@code 3[b]}</li>
 *   <li>{@code [xxx]} 里的 xxx 不是真正的变量代码（把一整段算式错误包进了方括号）</li>
 *   <li>裸 ASCII 变量（Cu/Zn/...）引用了白名单里没有的 code</li>
 * </ul>
 *
 * <p>这些结构错走到求值阶段会悄悄算成 0 或 null，结果漂移但无人报错。
 * Validator 在列表页 toDto 里跑一次，抓到即标红提示用户修正。
 */
@Component
public class FormulaValidator {
  private static final Logger log = LoggerFactory.getLogger(FormulaValidator.class);

  private final PriceVariableMapper priceVariableMapper;
  /** V36：行局部占位符从 registry 动态拿（之前是硬编码 __material / __scrap 两个） */
  private final RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry;
  /**
   * code 白名单：variable_code 表的缓存。PostConstruct 一次性装载。
   *
   * <p>注意：这里 <b>不</b> 再把行局部占位符拍进白名单——占位符 code 可能在运行时
   * 通过 admin 接口增减，白名单是启动快照，会漏。改到 {@link #checkCodeWhitelist}
   * 里每次查 {@link RowLocalPlaceholderRegistry#isKnown(String)}（registry 内部有缓存）。
   */
  private volatile Set<String> codeWhitelist;

  public FormulaValidator(
      PriceVariableMapper priceVariableMapper,
      RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry) {
    this.priceVariableMapper = priceVariableMapper;
    this.rowLocalPlaceholderRegistry = rowLocalPlaceholderRegistry;
  }

  @PostConstruct
  public void init() {
    Set<String> s = new HashSet<>();
    priceVariableMapper
        .selectList(new QueryWrapper<PriceVariable>().eq("status", "active"))
        .stream()
        .map(PriceVariable::getVariableCode)
        .forEach(s::add);
    this.codeWhitelist = Set.copyOf(s);
    log.info("FormulaValidator 加载 {} 条 code 白名单（行局部占位符运行时查 registry）",
        codeWhitelist.size());
  }

  /**
   * 校验规范化后的公式字符串。
   *
   * @param normalized {@link FormulaNormalizer#normalize(String)} 的返回值
   * @throws FormulaSyntaxException 语法错或 code 不在白名单
   */
  public void validate(String normalized) {
    if (normalized == null || normalized.isBlank()) {
      return;
    }
    List<Token> tokens = strictTokenize(normalized);
    checkGrammar(tokens, normalized);
    checkCodeWhitelist(tokens, normalized);
  }

  // ============================ 阶段 A：严格 tokenize ============================

  /**
   * 严格分词 —— 跟项目原有 {@link com.sanhua.marketingcost.formula.registry.ExpressionEvaluator}
   * 不同的是：遇到未知字符直接抛，不静默 i++ 跳过。
   */
  private List<Token> strictTokenize(String expr) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = expr.length();
    while (i < n) {
      char c = expr.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '[') {
        int end = expr.indexOf(']', i);
        if (end < 0) {
          throw new FormulaSyntaxException("方括号未闭合（位置=" + i + "）：" + expr);
        }
        out.add(Token.var(expr.substring(i + 1, end), i));
        i = end + 1;
        continue;
      }
      if (c == '(') {
        out.add(Token.lp(i));
        i++;
        continue;
      }
      if (c == ')') {
        out.add(Token.rp(i));
        i++;
        continue;
      }
      if (c == '+' || c == '-' || c == '*' || c == '/') {
        out.add(Token.op(String.valueOf(c), i));
        i++;
        continue;
      }
      if (Character.isDigit(c) || c == '.') {
        int s = i;
        while (i < n && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) {
          i++;
        }
        out.add(Token.num(expr.substring(s, i), s));
        continue;
      }
      if (c == '_' || Character.isLetter(c)) {
        int s = i;
        while (i < n
            && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
          i++;
        }
        // 裸 ASCII/下划线标识符也当变量 —— 白名单阶段一并校验
        out.add(Token.var(expr.substring(s, i), s));
        continue;
      }
      throw new FormulaSyntaxException("非法字符（位置=" + i + "，字符=" + c + "）：" + expr);
    }
    return out;
  }

  // ============================ 阶段 B：语法状态机 ============================

  /**
   * 状态：EXPECT_VALUE = 期待值/LPAREN/一元 +-；EXPECT_OP = 期待 OP/RPAREN/结尾。
   *
   * <pre>
   *   EXPECT_VALUE + VAR/NUM   → EXPECT_OP
   *   EXPECT_VALUE + LPAREN    → EXPECT_VALUE  (进入新子表达式)
   *   EXPECT_VALUE + OP(+|-)   → EXPECT_VALUE  (一元)
   *   EXPECT_VALUE + OP(*|/)   → 抛
   *   EXPECT_VALUE + RPAREN    → 抛
   *   EXPECT_OP    + OP        → EXPECT_VALUE
   *   EXPECT_OP    + RPAREN    → EXPECT_OP     (子表达式结束)
   *   EXPECT_OP    + VAR/NUM/LPAREN → 抛  ← 相邻 value 缺运算符（id=13 的脏点）
   * </pre>
   */
  private void checkGrammar(List<Token> tokens, String src) {
    State state = State.EXPECT_VALUE;
    int parenDepth = 0;
    for (Token t : tokens) {
      switch (state) {
        case EXPECT_VALUE -> {
          switch (t.type) {
            case VAR, NUM -> state = State.EXPECT_OP;
            case LPAREN -> parenDepth++;
            case OP -> {
              if (!"+".equals(t.text) && !"-".equals(t.text)) {
                throw new FormulaSyntaxException(
                    "运算符位置错（位置=" + t.pos + "，" + t.text + "）：" + src);
              }
              // 一元 +/-，状态保持 EXPECT_VALUE
            }
            case RPAREN -> throw new FormulaSyntaxException(
                "右括号位置错（位置=" + t.pos + "）：" + src);
          }
        }
        case EXPECT_OP -> {
          switch (t.type) {
            case VAR, NUM, LPAREN -> throw new FormulaSyntaxException(
                "缺运算符（位置=" + t.pos + "，"
                    + describe(t) + " 前需要 +/-/*/ 之一）：" + src);
            case OP -> state = State.EXPECT_VALUE;
            case RPAREN -> {
              parenDepth--;
              if (parenDepth < 0) {
                throw new FormulaSyntaxException("右括号过多（位置=" + t.pos + "）：" + src);
              }
            }
          }
        }
      }
    }
    if (state == State.EXPECT_VALUE) {
      throw new FormulaSyntaxException("表达式以运算符结尾或有空分支：" + src);
    }
    if (parenDepth != 0) {
      throw new FormulaSyntaxException("括号不平衡（差值=" + parenDepth + "）：" + src);
    }
  }

  private static String describe(Token t) {
    return switch (t.type) {
      case VAR -> "变量[" + t.text + "]";
      case NUM -> "数字" + t.text;
      case LPAREN -> "左括号";
      default -> t.text;
    };
  }

  // ============================ 阶段 C：code 白名单校验 ============================

  private void checkCodeWhitelist(List<Token> tokens, String src) {
    for (Token t : tokens) {
      if (t.type != TokenType.VAR) {
        continue;
      }
      // 白名单：正规 variable_code 或已登记的行局部占位符（后者支持运行时扩展）
      if (codeWhitelist.contains(t.text)
          || rowLocalPlaceholderRegistry.isKnown(t.text)) {
        continue;
      }
      throw new FormulaSyntaxException(
          "未知变量 [" + t.text + "]（位置=" + t.pos + "）：" + src);
    }
  }

  private enum State {
    EXPECT_VALUE, EXPECT_OP
  }

  private enum TokenType {
    VAR, NUM, OP, LPAREN, RPAREN
  }

  private static final class Token {
    final TokenType type;
    final String text;
    final int pos;

    Token(TokenType t, String s, int p) {
      this.type = t;
      this.text = s;
      this.pos = p;
    }

    static Token var(String s, int p) {
      return new Token(TokenType.VAR, s, p);
    }

    static Token num(String s, int p) {
      return new Token(TokenType.NUM, s, p);
    }

    static Token op(String s, int p) {
      return new Token(TokenType.OP, s, p);
    }

    static Token lp(int p) {
      return new Token(TokenType.LPAREN, "(", p);
    }

    static Token rp(int p) {
      return new Token(TokenType.RPAREN, ")", p);
    }
  }
}
