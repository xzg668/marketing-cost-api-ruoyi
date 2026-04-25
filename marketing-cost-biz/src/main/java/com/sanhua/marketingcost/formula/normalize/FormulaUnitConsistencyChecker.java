package com.sanhua.marketingcost.formula.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 公式单位一致性静态检查器 —— 扫描规范化后的公式字符串，识别"元/吨旧口径"的遗留写法。
 *
 * <p>系统已统一到 kg 口径：
 * <ul>
 *   <li>{@code blank_weight} / {@code net_weight} 在变量层通过 {@code unitScale=0.001}
 *       自动从"克"换算到"千克"；公式里不应再出现 {@code /1000} 这种二次换算</li>
 *   <li>所有元/kg 的常数（如单位加工损耗）应以"元/kg"数量级出现，比如 {@code +0.05}、
 *       {@code +1.1}；若出现 {@code +50}、{@code +1100} 这类大数，往往是 Excel 旧的
 *       "元/吨"习惯没跟上口径转换</li>
 * </ul>
 *
 * <p>本检查器只产 warning（不阻断保存），让用户自己看一眼；真要算出来不对，前端的
 * 「保存前变更影响预览」会再卡一道。
 */
@Component
public class FormulaUnitConsistencyChecker {

  /** 除以 1000：疑似"克→千克"二次换算 */
  private static final Pattern DIV_1000 = Pattern.compile("/\\s*1000\\b");

  /** 乘以 1000：疑似"千克→克"反向换算 */
  private static final Pattern MUL_1000 = Pattern.compile("\\*\\s*1000\\b");

  /**
   * 数字字面量 —— 扫所有独立数字（整数或小数），前面不紧跟字母/下划线（避免误扫变量名里的数字）。
   *
   * <p>规则二筛选阈值：{@code value >= 10} 且非 1000（1000 已被除法/乘法规则单独处理）。
   * 正常 kg 口径公式里的系数都是小数（1.45、0.93、1.02、0.915、0.59、0.41 等），几乎不会 ≥ 10；
   * 一旦出现 {@code 50、800、1100} 这种，基本就是 Excel 旧的"元/吨"遗留。
   */
  private static final Pattern NUMERIC_LITERAL =
      Pattern.compile("(?<![A-Za-z_\\[])(\\d+(?:\\.\\d+)?)");

  /**
   * 扫描规范化表达式，把所有疑似"旧口径"写法收集成 warning 文案。
   *
   * @param normalizedExpr FormulaNormalizer 输出的表达式（变量已 {@code [code]} 化）
   * @return warning 列表；空列表表示没有可疑写法
   */
  public List<String> check(String normalizedExpr) {
    List<String> warnings = new ArrayList<>();
    if (normalizedExpr == null || normalizedExpr.isEmpty()) {
      return warnings;
    }

    if (DIV_1000.matcher(normalizedExpr).find()) {
      warnings.add(
          "⚠️ 公式里出现 /1000 —— 系统内「下料重量 / 净重」已在变量层自动换算到千克，"
              + "公式里再 /1000 通常是 Excel 旧的'克'口径残留，请确认是否多余。");
    }
    if (MUL_1000.matcher(normalizedExpr).find()) {
      warnings.add(
          "⚠️ 公式里出现 *1000 —— 系统内变量已是千克口径，反向乘 1000 不常见，请确认。");
    }

    Matcher m = NUMERIC_LITERAL.matcher(normalizedExpr);
    List<String> bigConstants = new ArrayList<>();
    while (m.find()) {
      String num = m.group(1);
      // 排除正好是 1000（已被 /1000 *1000 规则单独报过）
      if ("1000".equals(num)) {
        continue;
      }
      double v;
      try {
        v = Double.parseDouble(num);
      } catch (NumberFormatException ignored) {
        continue;
      }
      // 阈值 10：正常 kg 口径系数都小于 10；≥ 10 大概率是元/吨旧常数
      if (v >= 10) {
        bigConstants.add(num);
      }
    }
    if (!bigConstants.isEmpty()) {
      warnings.add(
          "⚠️ 公式里有裸常数 " + String.join("、", bigConstants) + " ≥ 10，"
              + "疑似 Excel 旧的'元/吨'口径（例：+50 元/吨 应改为 +0.05 元/kg）。"
              + "如果是正常的加工损耗/代理费，请忽略本提示。");
    }

    return warnings;
  }
}
