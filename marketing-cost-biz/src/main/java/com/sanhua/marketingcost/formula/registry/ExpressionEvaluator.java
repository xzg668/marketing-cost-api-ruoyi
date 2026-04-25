package com.sanhua.marketingcost.formula.registry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 共享表达式求值器 —— 支持四则运算与括号，混合中文 [变量] 与 ASCII 变量。
 *
 * <p>抽取自 {@link com.sanhua.marketingcost.service.impl.PriceLinkedCalcServiceImpl}，
 * 复用给 {@code FormulaRefResolver}（递归取嵌套变量公式值）使用。
 *
 * <p>变量识别规则：
 * <ul>
 *   <li>{@code [变量名]} 中括号显式声明（支持中文/英文/下划线）—— 优先识别</li>
 *   <li>裸 ASCII 标识符 {@code [A-Za-z_][A-Za-z0-9_]*} —— 兼容旧表达式</li>
 * </ul>
 */
public final class ExpressionEvaluator {

  /** 中文/英文/下划线变量，需用 [...] 显式包裹 */
  static final Pattern BRACKET_PATTERN =
      Pattern.compile("\\[([\\u4e00-\\u9fa5A-Za-z_][\\u4e00-\\u9fa5A-Za-z0-9_]*)\\]");

  /** 旧的裸 ASCII 标识符（无 [) 兼容路径 */
  static final Pattern ASCII_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private ExpressionEvaluator() {}

  /** 提取表达式内全部变量名（去重，保持出现顺序） */
  public static LinkedHashSet<String> extractVariables(String expr) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    if (expr == null || expr.isBlank()) {
      return tokens;
    }
    // 1) 先抽 [中文/英文]，并用空白替换掉避免 ASCII 模式重复抽取
    StringBuilder remaining = new StringBuilder(expr);
    Matcher bracket = BRACKET_PATTERN.matcher(expr);
    while (bracket.find()) {
      tokens.add(bracket.group(1));
      // 用同长度空白填充，保留位置
      for (int i = bracket.start(); i < bracket.end(); i++) {
        remaining.setCharAt(i, ' ');
      }
    }
    // 2) 在剩余串里抽 ASCII 标识符
    Matcher ascii = ASCII_PATTERN.matcher(remaining.toString());
    while (ascii.find()) {
      tokens.add(ascii.group());
    }
    return tokens;
  }

  /** 求值；遇缺失变量按 0 处理；除零返回 null */
  public static BigDecimal evaluate(String expr, Map<String, BigDecimal> values) {
    if (expr == null || expr.isBlank()) {
      return null;
    }
    // 把 [中文变量] 替换成内部占位 token: __V0__/__V1__... 然后走原 tokenize
    LinkedHashSet<String> bracketed = new LinkedHashSet<>();
    Matcher m = BRACKET_PATTERN.matcher(expr);
    StringBuilder normalized = new StringBuilder();
    int last = 0;
    while (m.find()) {
      normalized.append(expr, last, m.start());
      String name = m.group(1);
      int idx = bracketed.size();
      // 仅当首次出现时新增；linkedhashset add 返回 false 则已存在
      if (bracketed.add(name)) {
        // ok
      }
      // 找到当前 name 的索引
      int existingIdx = 0;
      for (String n : bracketed) {
        if (n.equals(name)) {
          break;
        }
        existingIdx++;
      }
      normalized.append("__V").append(existingIdx).append("__");
      last = m.end();
    }
    normalized.append(expr.substring(last));

    // 把 placeholder 映射回真实值
    Map<String, BigDecimal> merged = new java.util.HashMap<>(values);
    int i = 0;
    for (String name : bracketed) {
      merged.put("__V" + i + "__", values.get(name));
      i++;
    }
    return evaluateAscii(normalized.toString(), merged);
  }

  // ---- 以下是 ASCII shunting-yard 实现 ----

  private static BigDecimal evaluateAscii(String expr, Map<String, BigDecimal> variables) {
    List<Token> tokens = tokenize(expr);
    List<Token> output = new ArrayList<>();
    Deque<Token> ops = new ArrayDeque<>();
    Token previous = null;
    for (Token t : tokens) {
      switch (t.type) {
        case NUMBER, VARIABLE -> {
          output.add(t);
          previous = t;
        }
        case OPERATOR -> {
          Token op = t;
          if ("-".equals(op.text) && (previous == null
              || previous.type == TokenType.OPERATOR
              || previous.type == TokenType.LEFT_PAREN)) {
            op = Token.unary();
          }
          while (!ops.isEmpty()
              && ops.peek().type == TokenType.OPERATOR
              && precedence(ops.peek()) >= precedence(op)) {
            output.add(ops.pop());
          }
          ops.push(op);
          previous = op;
        }
        case LEFT_PAREN -> {
          ops.push(t);
          previous = t;
        }
        case RIGHT_PAREN -> {
          while (!ops.isEmpty() && ops.peek().type != TokenType.LEFT_PAREN) {
            output.add(ops.pop());
          }
          if (!ops.isEmpty() && ops.peek().type == TokenType.LEFT_PAREN) {
            ops.pop();
          }
          previous = t;
        }
        default -> {
        }
      }
    }
    while (!ops.isEmpty()) {
      output.add(ops.pop());
    }
    Deque<BigDecimal> stack = new ArrayDeque<>();
    for (Token t : output) {
      switch (t.type) {
        case NUMBER -> stack.push(t.number);
        case VARIABLE -> {
          BigDecimal v = variables.get(t.text);
          stack.push(v == null ? BigDecimal.ZERO : v);
        }
        case OPERATOR -> {
          if ("NEG".equals(t.text)) {
            BigDecimal v = stack.pop();
            stack.push(v.negate());
          } else {
            BigDecimal r = stack.pop();
            BigDecimal l = stack.pop();
            BigDecimal applied = apply(t.text, l, r);
            if (applied == null) {
              return null;
            }
            stack.push(applied);
          }
        }
        default -> {
        }
      }
    }
    return stack.size() == 1 ? stack.pop() : null;
  }

  private static List<Token> tokenize(String expr) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < expr.length()) {
      char ch = expr.charAt(i);
      if (Character.isWhitespace(ch)) {
        i++;
        continue;
      }
      if (Character.isDigit(ch) || ch == '.') {
        int s = i;
        while (i < expr.length() && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) {
          i++;
        }
        tokens.add(Token.number(new BigDecimal(expr.substring(s, i))));
        continue;
      }
      if (Character.isLetter(ch) || ch == '_') {
        int s = i;
        while (i < expr.length()
            && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
          i++;
        }
        tokens.add(Token.variable(expr.substring(s, i)));
        continue;
      }
      if (ch == '(') {
        tokens.add(Token.leftParen());
        i++;
        continue;
      }
      if (ch == ')') {
        tokens.add(Token.rightParen());
        i++;
        continue;
      }
      if ("+-*/".indexOf(ch) >= 0) {
        tokens.add(Token.operator(String.valueOf(ch)));
        i++;
        continue;
      }
      i++;
    }
    return tokens;
  }

  private static int precedence(Token t) {
    if ("NEG".equals(t.text)) {
      return 3;
    }
    return ("*".equals(t.text) || "/".equals(t.text)) ? 2 : 1;
  }

  private static BigDecimal apply(String op, BigDecimal l, BigDecimal r) {
    return switch (op) {
      case "+" -> l.add(r);
      case "-" -> l.subtract(r);
      case "*" -> l.multiply(r);
      case "/" -> r.compareTo(BigDecimal.ZERO) == 0
          ? null : l.divide(r, 10, RoundingMode.HALF_UP);
      default -> null;
    };
  }

  private enum TokenType { NUMBER, VARIABLE, OPERATOR, LEFT_PAREN, RIGHT_PAREN }

  private static final class Token {
    final TokenType type;
    final String text;
    final BigDecimal number;

    private Token(TokenType type, String text, BigDecimal number) {
      this.type = type;
      this.text = text;
      this.number = number;
    }

    static Token number(BigDecimal n) {
      return new Token(TokenType.NUMBER, null, n);
    }
    static Token variable(String s) {
      return new Token(TokenType.VARIABLE, s, null);
    }
    static Token operator(String s) {
      return new Token(TokenType.OPERATOR, s, null);
    }
    static Token unary() {
      return new Token(TokenType.OPERATOR, "NEG", null);
    }
    static Token leftParen() {
      return new Token(TokenType.LEFT_PAREN, "(", null);
    }
    static Token rightParen() {
      return new Token(TokenType.RIGHT_PAREN, ")", null);
    }
  }
}
