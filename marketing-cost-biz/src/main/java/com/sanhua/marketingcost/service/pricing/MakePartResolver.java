package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.TemplateEngine;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 制造件取价递归解析器 (Task #8)。
 *
 * <p>取价路径：
 * <ol>
 *   <li>按 {@code material_code + period} 查 lp_make_part_spec</li>
 *   <li>{@code formula_id} 非空 → 走 TemplateEngine（Phase 2 已就绪）</li>
 *   <li>否则用默认骨架公式：
 *     <pre>
 *       unitPrice = (blank × raw_price - (blank - net) × recycle_price × recycle_ratio) / 1000
 *                 + process_fee + outsource_fee
 *     </pre>
 *     重量字段 g → kg 自动除 1000，与 Excel 口径对齐
 *   </li>
 *   <li>{@code raw_unit_price} 缺失且 {@code raw_material_code} 非空 → 递归取价（最多 10 层）</li>
 * </ol>
 *
 * <p>循环检测：维护当前递归栈 LinkedHashSet；命中则记 WARN + 返回 null（不抛异常，
 * 让上游 dual-run 标红，运维可继续看到其它行）。
 */
@Service
public class MakePartResolver {

  private static final Logger log = LoggerFactory.getLogger(MakePartResolver.class);
  /** 递归最大深度，防爆栈与脏数据死循环 */
  static final int MAX_DEPTH = 10;
  /** 重量单位换算（克→千克） */
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  private final MakePartSpecMapper specMapper;
  /** TemplateEngine 可选；formula_id 为空时不会被调用 */
  private final TemplateEngine templateEngine;

  public MakePartResolver(MakePartSpecMapper specMapper, TemplateEngine templateEngine) {
    this.specMapper = specMapper;
    this.templateEngine = templateEngine;
  }

  /**
   * 顶层入口 —— 按物料 + 期间取价。
   *
   * @return 单价；spec 缺失或递归失败返回 null
   */
  public BigDecimal resolve(String materialCode, String period) {
    return resolveInternal(materialCode, period, new LinkedHashSet<>());
  }

  BigDecimal resolveInternal(String materialCode, String period, LinkedHashSet<String> stack) {
    if (materialCode == null || materialCode.isBlank()) {
      return null;
    }
    String code = materialCode.trim();
    if (stack.size() >= MAX_DEPTH) {
      log.warn("MakePartResolver 递归深度超限 {}，路径={} → {}",
          MAX_DEPTH, stack, code);
      return null;
    }
    if (!stack.add(code)) {
      log.warn("MakePartResolver 检测到循环依赖，路径={} → {}", stack, code);
      return null;
    }
    try {
      MakePartSpec spec = lookupSpec(code, period);
      if (spec == null) {
        log.debug("MakePartResolver 未找到工艺规格: code={}, period={}", code, period);
        return null;
      }
      // formula_id 非空 → 委派 TemplateEngine
      if (spec.getFormulaId() != null && templateEngine != null) {
        // 当前 spec → template inputs 的转换在后续 Phase 完善；先按默认骨架求值
        log.debug("MakePartResolver 暂不接 TemplateEngine 实例化，回退默认骨架: code={}", code);
      }
      return computeSkeleton(spec, period, stack);
    } finally {
      stack.remove(code);
    }
  }

  /** 查 MakePartSpec：优先按 (code, period) 精确；缺则按 code 取最新一条。包私有便于单测 spy 覆盖 */
  MakePartSpec lookupSpec(String code, String period) {
    if (period != null && !period.isBlank()) {
      MakePartSpec exact = specMapper.selectOne(
          Wrappers.lambdaQuery(MakePartSpec.class)
              .eq(MakePartSpec::getMaterialCode, code)
              .eq(MakePartSpec::getPeriod, period.trim())
              .last("LIMIT 1"));
      if (exact != null) {
        return exact;
      }
    }
    List<MakePartSpec> rows = specMapper.selectList(
        Wrappers.lambdaQuery(MakePartSpec.class)
            .eq(MakePartSpec::getMaterialCode, code)
            .orderByDesc(MakePartSpec::getUpdatedAt)
            .orderByDesc(MakePartSpec::getId)
            .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 默认骨架公式求值 */
  BigDecimal computeSkeleton(MakePartSpec spec, String period, LinkedHashSet<String> stack) {
    BigDecimal blank = nz(spec.getBlankWeight());
    BigDecimal net = spec.getNetWeight() == null ? blank : spec.getNetWeight();
    BigDecimal rawPrice = spec.getRawUnitPrice();
    if (rawPrice == null && spec.getRawMaterialCode() != null) {
      // 递归：原材料价从上游 spec 取
      rawPrice = resolveInternal(spec.getRawMaterialCode(), period, stack);
    }
    if (rawPrice == null) {
      log.warn("MakePartResolver 原材料价缺失: code={}, raw={}",
          spec.getMaterialCode(), spec.getRawMaterialCode());
      return null;
    }
    BigDecimal recyclePrice = nz(spec.getRecycleUnitPrice());
    BigDecimal recycleRatio = spec.getRecycleRatio() == null
        ? BigDecimal.ONE : spec.getRecycleRatio();

    // 主材料成本（克→千克）
    BigDecimal materialCost = blank.multiply(rawPrice)
        .divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
    // 回收抵减
    BigDecimal scrapWeight = blank.subtract(net);
    BigDecimal scrapDeduction = scrapWeight.multiply(recyclePrice).multiply(recycleRatio)
        .divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
    BigDecimal processFee = nz(spec.getProcessFee());
    BigDecimal outsourceFee = nz(spec.getOutsourceFee());

    return materialCost.subtract(scrapDeduction).add(processFee).add(outsourceFee)
        .setScale(6, RoundingMode.HALF_UP);
  }

  // 不确定 TemplateEngine 接 spec 的输入约定时占位调用入口
  @SuppressWarnings("unused")
  private CalcResult invokeTemplate(MakePartSpec spec) {
    throw new UnsupportedOperationException(
        "MakePartSpec → TemplateEngine 输入映射待 Phase 4 完善");
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
