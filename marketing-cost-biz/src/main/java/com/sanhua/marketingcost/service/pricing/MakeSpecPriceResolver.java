package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 自制件取价 Resolver —— v1.2 (T08) 起含原料递归。
 *
 * <p>取价算法：
 * <pre>
 *   material_cost = blank_weight × raw_unit_price / 1000   (g → kg 换算)
 *   unit_price    = material_cost + process_fee + outsource_fee
 * </pre>
 *
 * <p>raw_unit_price 来源（按优先级）：
 * <ol>
 *   <li>spec.raw_unit_price 非空 → 直接用（手填覆盖）</li>
 *   <li>spec.raw_material_code 非空 → 走完整 Router + Resolver 递归取价</li>
 *   <li>都为空 → material_cost 视为 0（不算 fail，由 process/outsource 兜底）</li>
 * </ol>
 *
 * <p>循环依赖：本 Resolver 是 {@code List<PriceResolver>} 中之一，注入需要 {@code @Lazy}
 * 才能破环。Spring 注入时给个代理，递归调时才解析真实 list。
 *
 * <p>防死循环：维护 ThreadLocal LinkedHashSet 跟踪当前递归栈，
 * 重复料号或深度 > {@link #MAX_DEPTH} 直接 miss（不抛异常，让上层标 ERROR）。
 *
 * <p>废料抵扣（T09）：
 * <ol>
 *   <li>spec.recycle_code → 查 lp_price_scrap by (scrap_code, pricing_month=当月)</li>
 *   <li>表里查不到 → fallback spec.recycle_unit_price 手填值</li>
 *   <li>都没 → 抵扣 0，remark 加 "缺废料价(recycle_code=X)"</li>
 * </ol>
 * 抵扣额：{@code (blank - net) × scrap_price / 1000}（g→kg 换算）
 */
@Service
public class MakeSpecPriceResolver implements PriceResolver {

  private static final Logger log = LoggerFactory.getLogger(MakeSpecPriceResolver.class);
  private static final int MAX_DEPTH = 5;
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");
  /** 跨 resolve 调用维护递归栈；同线程内自然连续，外层退出时 cleanup */
  private static final ThreadLocal<LinkedHashSet<String>> RECURSION_STACK =
      ThreadLocal.withInitial(LinkedHashSet::new);

  private final MakePartSpecMapper makePartSpecMapper;
  private final PriceScrapMapper priceScrapMapper;
  private final MaterialPriceRouterService routerService;
  /** 注入所有 PriceResolver（含自身），@Lazy 破环 */
  private final List<PriceResolver> allResolvers;
  /** 桶 → Resolver 缓存，首次 resolve 时构建（避开 ctor 循环） */
  private volatile Map<PriceTypeEnum, PriceResolver> resolverMap;

  public MakeSpecPriceResolver(
      MakePartSpecMapper makePartSpecMapper,
      PriceScrapMapper priceScrapMapper,
      MaterialPriceRouterService routerService,
      @Lazy List<PriceResolver> allResolvers) {
    this.makePartSpecMapper = makePartSpecMapper;
    this.priceScrapMapper = priceScrapMapper;
    this.routerService = routerService;
    this.allResolvers = allResolvers;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.MAKE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String rawCode = item.getPartCode();
    if (rawCode == null || rawCode.isBlank()) {
      return PriceResolveResult.miss("partCode 为空");
    }
    String code = rawCode.trim();
    LinkedHashSet<String> stack = RECURSION_STACK.get();
    boolean isOuter = stack.isEmpty();
    if (stack.size() >= MAX_DEPTH) {
      log.warn("MakeSpec 递归深度超限 {}, 路径={} → {}", MAX_DEPTH, stack, code);
      return PriceResolveResult.miss(
          "自制件递归深度超限(" + MAX_DEPTH + "): 路径=" + stack);
    }
    if (!stack.add(code)) {
      log.warn("MakeSpec 递归循环, 路径={} → {}", stack, code);
      return PriceResolveResult.miss("自制件递归循环: " + stack + " → " + code);
    }
    try {
      MakePartSpec spec = lookupSpec(code);
      if (spec == null) {
        return PriceResolveResult.miss("lp_make_part_spec 无该料号: " + code);
      }
      BigDecimal materialCost = computeMaterialCost(oaNo, spec);
      if (materialCost == null) {
        return PriceResolveResult.miss(
            "自制件原料取价失败: raw=" + spec.getRawMaterialCode());
      }
      // T09：废料抵扣，scrapNote 非空时进 remark（用 hit 工厂的话 remark 会被吃掉，所以手工 new）
      ScrapDeduction scrap = computeScrapDeduction(spec);
      BigDecimal unitPrice = materialCost
          .subtract(scrap.amount)
          .add(nz(spec.getProcessFee()))
          .add(nz(spec.getOutsourceFee()));
      String source = PriceTypeEnum.MAKE.getDbText();
      String remark = scrap.note == null ? "" : scrap.note;
      return new PriceResolveResult(unitPrice, source, remark);
    } finally {
      stack.remove(code);
      if (isOuter) {
        // 最外层退出，清掉 ThreadLocal 防止线程复用时泄漏
        RECURSION_STACK.remove();
      }
    }
  }

  /**
   * 算材料成本：blank × raw_price / 1000（g→kg）。
   *
   * <p>raw_price 来源：spec.raw_unit_price 非空 → 用；否则递归取 raw_material_code 的价；
   * 都为空 → 0（不算 fail，由 process/outsource 兜底，避免无毛重的"纯加工件"被卡）。
   */
  private BigDecimal computeMaterialCost(String oaNo, MakePartSpec spec) {
    BigDecimal blank = nz(spec.getBlankWeight());
    if (blank.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal rawPrice = spec.getRawUnitPrice();
    if (rawPrice == null) {
      String rawCode = spec.getRawMaterialCode();
      if (rawCode == null || rawCode.isBlank()) {
        return BigDecimal.ZERO; // 无原料编码也无 raw 价 → 视为纯加工
      }
      rawPrice = recursiveLookupRawPrice(oaNo, rawCode.trim());
      if (rawPrice == null) {
        return null;
      }
    }
    return blank.multiply(rawPrice).divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
  }

  /**
   * 递归调主取价拿 raw 单价：走完整 Router + Resolver 流程，按 priority 升序首个命中。
   * 全 fallthrough 返 null，让上层兜底为 miss。
   */
  private BigDecimal recursiveLookupRawPrice(String oaNo, String rawCode) {
    LocalDate quoteDate = LocalDate.now();
    String period = quoteDate.toString().substring(0, 7);
    List<PriceTypeRoute> candidates = routerService.listCandidates(rawCode, period, quoteDate);
    if (candidates.isEmpty()) {
      log.warn("自制件原料无路由: raw={}", rawCode);
      return null;
    }
    CostRunPartItemDto rawItem = new CostRunPartItemDto();
    rawItem.setPartCode(rawCode);
    rawItem.setPartQty(BigDecimal.ONE);
    Map<PriceTypeEnum, PriceResolver> map = resolverMap();
    for (PriceTypeRoute r : candidates) {
      PriceResolver resolver = map.get(r.priceType());
      if (resolver == null) {
        continue;
      }
      PriceResolveResult result = resolver.resolve(oaNo, rawItem, r);
      if (result.unitPrice() != null) {
        return result.unitPrice();
      }
    }
    log.warn("自制件原料 Resolver 全 miss: raw={}, candidates={}", rawCode, candidates.size());
    return null;
  }

  /**
   * 算废料抵扣：scrap_weight = blank - net；scrap_price 来源链：
   * 1) lp_price_scrap by (recycle_code, current month) 2) spec.recycle_unit_price 3) 都没 → 0+remark
   */
  private ScrapDeduction computeScrapDeduction(MakePartSpec spec) {
    BigDecimal blank = nz(spec.getBlankWeight());
    BigDecimal net = spec.getNetWeight() == null ? blank : spec.getNetWeight();
    BigDecimal scrapWeight = blank.subtract(net);
    if (scrapWeight.signum() <= 0) {
      return new ScrapDeduction(BigDecimal.ZERO, null);
    }
    String recycleCode = spec.getRecycleCode();
    if (recycleCode == null || recycleCode.isBlank()) {
      return new ScrapDeduction(BigDecimal.ZERO, null);
    }
    String month = YearMonth.now().toString();
    PriceScrap scrapRow = priceScrapMapper.selectOne(
        Wrappers.lambdaQuery(PriceScrap.class)
            .eq(PriceScrap::getScrapCode, recycleCode.trim())
            .eq(PriceScrap::getPricingMonth, month)
            .orderByDesc(PriceScrap::getId)
            .last("LIMIT 1"));
    BigDecimal price = scrapRow == null ? null : scrapRow.getRecyclePrice();
    String note = null;
    if (price == null) {
      // fallback 用 spec 里的手填值（Excel 导入时填过）
      price = spec.getRecycleUnitPrice();
    }
    if (price == null) {
      // 两个源都缺 → 不抵扣 + 标记
      log.warn("自制件废料价缺失: code={}, recycle_code={}", spec.getMaterialCode(), recycleCode);
      return new ScrapDeduction(BigDecimal.ZERO,
          "缺废料价(recycle_code=" + recycleCode + ")");
    }
    BigDecimal amount = scrapWeight.multiply(price)
        .divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
    return new ScrapDeduction(amount, note);
  }

  /** 废料抵扣结果：amount=抵扣金额；note=remark（缺价时填，正常 null） */
  private record ScrapDeduction(BigDecimal amount, String note) {}

  /** 首次调用时构建 桶→Resolver 缓存（@Lazy 注入的 List 此时已可访问真实 bean） */
  private Map<PriceTypeEnum, PriceResolver> resolverMap() {
    Map<PriceTypeEnum, PriceResolver> m = resolverMap;
    if (m == null) {
      m = new EnumMap<>(PriceTypeEnum.class);
      for (PriceResolver r : allResolvers) {
        m.put(r.priceType(), r);
      }
      resolverMap = m;
    }
    return m;
  }

  /**
   * 查 spec：先 (code) 取最新一条；不做 period 严格过滤
   * （v1 数据所有期间放宽，按 effective_from / id 倒排取首条）。
   */
  private MakePartSpec lookupSpec(String code) {
    List<MakePartSpec> rows =
        makePartSpecMapper.selectList(
            Wrappers.lambdaQuery(MakePartSpec.class)
                .eq(MakePartSpec::getMaterialCode, code)
                .orderByDesc(MakePartSpec::getEffectiveFrom)
                .orderByDesc(MakePartSpec::getId)
                .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
