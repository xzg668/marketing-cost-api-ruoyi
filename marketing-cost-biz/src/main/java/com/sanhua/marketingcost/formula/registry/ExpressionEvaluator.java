package com.sanhua.marketingcost.formula.registry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 原稿所需的四则运算、ROUND、INT；校验、变量提取和求值共用同一语法。 */
public final class ExpressionEvaluator {
  // 中间除法保留34位有效数字；只有原公式的ROUND和最终业务输出决定舍入位置。
  private static final MathContext DIVISION_PRECISION = MathContext.DECIMAL128;

  private ExpressionEvaluator() {}

  public static LinkedHashSet<String> extractVariables(String expression) {
    if (expression == null || expression.isBlank()) return new LinkedHashSet<>();
    Parser parser = new Parser(expression, Map.of(), false);
    parser.parse();
    return parser.variables;
  }

  public static void validate(String expression) {
    if (expression == null || expression.isBlank()) throw new IllegalArgumentException("公式为空");
    new Parser(expression, Map.of(), false).parse();
  }

  /** 缺变量、非法语法及除零明确失败；真实0是正常数值。 */
  public static BigDecimal evaluate(String expression, Map<String, BigDecimal> values) {
    if (expression == null || expression.isBlank()) return null;
    return new Parser(expression, values == null ? Map.of() : values, true).parse();
  }

  private static final class Parser {
    private final String expression;
    private final Map<String, BigDecimal> values;
    private final boolean evaluate;
    private final LinkedHashSet<String> variables = new LinkedHashSet<>();
    private int position;
    private int depth;

    private Parser(String expression, Map<String, BigDecimal> values, boolean evaluate) {
      this.expression = expression;
      this.values = values;
      this.evaluate = evaluate;
    }

    private BigDecimal parse() {
      BigDecimal result = sum();
      whitespace();
      if (position != expression.length()) throw error("不支持的字符或缺少运算符");
      return result;
    }

    private BigDecimal sum() {
      BigDecimal result = product();
      while (true) {
        if (take('+')) result = result.add(product());
        else if (take('-')) result = result.subtract(product());
        else return result;
      }
    }

    private BigDecimal product() {
      BigDecimal result = unary();
      while (true) {
        if (take('*')) {
          BigDecimal right = unary();
          result = evaluate ? result.multiply(right) : BigDecimal.ZERO;
        } else if (take('/')) {
          BigDecimal right = unary();
          if (evaluate && right.signum() == 0) throw error("除数为0");
          result = evaluate ? result.divide(right, DIVISION_PRECISION) : BigDecimal.ZERO;
        } else return result;
      }
    }

    private BigDecimal unary() {
      if (++depth > 256) throw error("公式嵌套超过256层");
      try {
        if (take('+')) return unary();
        if (take('-')) return unary().negate();
        return primary();
      } finally {
        depth--;
      }
    }

    private BigDecimal primary() {
      whitespace();
      if (take('(')) {
        BigDecimal value = sum();
        require(')');
        return value;
      }
      if (position >= expression.length()) throw error("缺少数值");
      char first = expression.charAt(position);
      if (Character.isDigit(first) || first == '.') return number();
      if (take('[')) {
        int start = position;
        while (position < expression.length() && expression.charAt(position) != ']') position++;
        String name = expression.substring(start, position);
        require(']');
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) throw error("变量名非法：" + name);
        return variable(name);
      }
      if (Character.isLetter(first) || first == '_') {
        int start = position++;
        while (position < expression.length()) {
          char c = expression.charAt(position);
          if (!Character.isLetterOrDigit(c) && c != '_') break;
          position++;
        }
        String name = expression.substring(start, position);
        return take('(') ? function(name) : variable(name);
      }
      throw error("缺少数值或变量");
    }

    private BigDecimal function(String name) {
      String function = name.toUpperCase(Locale.ROOT);
      if (!function.equals("ROUND") && !function.equals("INT")) throw error("不支持的函数：" + name);
      List<BigDecimal> arguments = new ArrayList<>();
      arguments.add(sum());
      while (take(',')) arguments.add(sum());
      require(')');
      int expected = function.equals("ROUND") ? 2 : 1;
      if (arguments.size() != expected) throw error(function + "需要" + expected + "个参数");
      if (!evaluate) return BigDecimal.ZERO;
      if (function.equals("INT")) return arguments.getFirst().setScale(0, RoundingMode.FLOOR);
      int scale;
      try {
        scale = arguments.get(1).intValueExact();
      } catch (ArithmeticException exception) {
        throw error("ROUND小数位必须是整数");
      }
      if (Math.abs((long) scale) > 308) throw error("ROUND小数位超出支持范围");
      return arguments.getFirst().setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal number() {
      int start = position;
      while (position < expression.length()
          && (Character.isDigit(expression.charAt(position)) || expression.charAt(position) == '.'))
        position++;
      if (position < expression.length()
          && (expression.charAt(position) == 'e' || expression.charAt(position) == 'E')) {
        position++;
        if (position < expression.length()
            && (expression.charAt(position) == '+' || expression.charAt(position) == '-'))
          position++;
        while (position < expression.length() && Character.isDigit(expression.charAt(position)))
          position++;
      }
      try {
        return new BigDecimal(expression.substring(start, position));
      } catch (NumberFormatException exception) {
        throw error("数值格式非法");
      }
    }

    private BigDecimal variable(String name) {
      variables.add(name);
      if (!evaluate) return BigDecimal.ZERO;
      BigDecimal value = values.get(name);
      if (value == null) throw error("缺少变量：" + name);
      return value;
    }

    private boolean take(char character) {
      whitespace();
      if (position < expression.length() && expression.charAt(position) == character) {
        position++;
        return true;
      }
      return false;
    }

    private void require(char character) {
      if (!take(character)) throw error("缺少字符：" + character);
    }

    private void whitespace() {
      while (position < expression.length() && Character.isWhitespace(expression.charAt(position)))
        position++;
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + "，位置=" + (position + 1));
    }
  }
}
