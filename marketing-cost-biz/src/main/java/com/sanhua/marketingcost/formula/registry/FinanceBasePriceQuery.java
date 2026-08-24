package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 财务基价查询 helper（Plan B T3）—— 统一 FINANCE_FACTOR 解析的权威价查询。
 *
 * <p>背景：
 * 过去 {@code FactorVariableRegistryImpl.resolveFinanceFactor} 把 variable_name 当
 * short_name 查，且完全不过滤 price_source / business_unit_type，与 legacy 计算
 * 路径（factor_code + price_source + BU）完全对不上。本 helper 把"按 variable 元
 * 数据组装严格身份 SQL + 历史月份沿用"的能力收敛为一处，供新旧两套 registry 的
 * resolveFinance 分支直接调用。
 *
 * <p>四键契约（与 {@link com.sanhua.marketingcost.formula.registry.resolvers.FinanceBaseResolver}
 * 一致）：
 * <ul>
 *   <li>{@code factorCode} 或 {@code shortName} —— 二选一；factorCode 优先</li>
 *   <li>{@code priceSource} —— 必填，如 "平均价" / "长江现货平均价"</li>
 *   <li>{@code pricingMonth} —— 必填，YYYY-MM</li>
 *   <li>{@code buScoped && bu != null} —— 加 business_unit_type.eq 过滤</li>
 * </ul>
 *
 * <p>价格来源、因素身份和业务单元都必须严格匹配；月份按“小于等于核算月的最新一条”
 * 取值。财务明确要求报价不能因审批周期停止，因此没有当月价时沿用最近正式价，调用方
 * 通过返回记录的 {@code priceMonth} 判断并展示“沿用历史因素价”提醒。
 */
@Component
public class FinanceBasePriceQuery {

  private static final Logger log = LoggerFactory.getLogger(FinanceBasePriceQuery.class);

  private final FinanceBasePriceMapper mapper;

  public FinanceBasePriceQuery(FinanceBasePriceMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 在严格身份范围内查询不晚于核算月的最新一条基价记录。
   *
   * @param factorCode 权威因素编码（如 "Cu"），与 shortName 二选一
   * @param shortName  中文简称（如 "美国柜装黄铜"），factorCode 为 null/空白时生效
   * @param priceSource 价格来源标签（必填）
   * @param buScoped   是否强制按 bu 过滤；false 时忽略 bu 参数
   * @param pricingMonth 价期月（必填，YYYY-MM）
   * @param bu 当前租户 BU 类型（buScoped=true 时必填）
   * @param debugCode 调用方变量编码，用于 WARN 日志溯源
   * @return 命中记录；任一必填项缺失或未命中都返回 empty
   */
  public Optional<FinanceBasePrice> queryLatestBasePrice(
      String factorCode,
      String shortName,
      String priceSource,
      boolean buScoped,
      String pricingMonth,
      String bu,
      String debugCode) {

    // 1) factorCode 优先；factorCode 为空才用 shortName —— 两者都空即元数据不全
    boolean hasFactorCode = factorCode != null && !factorCode.isBlank();
    boolean hasShortName = shortName != null && !shortName.isBlank();
    if (!hasFactorCode && !hasShortName) {
      log.warn("FINANCE 查询 {} 缺 factorCode/shortName（至少配置一项）", debugCode);
      return Optional.empty();
    }

    // 2) priceSource 必填 —— 避免误选其他价源
    if (priceSource == null || priceSource.isBlank()) {
      log.warn("FINANCE 查询 {} 缺 priceSource（严格模式拒绝降级）", debugCode);
      return Optional.empty();
    }

    // 3) pricingMonth 必填 —— 月份缺失会拿到错误版本的价
    if (pricingMonth == null || pricingMonth.isBlank()) {
      log.warn("FINANCE 查询 {} 缺 pricingMonth（无法确定历史价沿用上限）", debugCode);
      return Optional.empty();
    }

    // 4) buScoped 开关下 bu 必填 —— 防止跨 BU 污染
    if (buScoped && (bu == null || bu.isBlank())) {
      log.warn("FINANCE 查询 {} buScoped=true 但当前请求无 businessUnitType", debugCode);
      return Optional.empty();
    }

    // 5) 组装 lambdaQuery；身份严格匹配，月份允许沿用不晚于核算月的最近正式价。
    var wrapper = Wrappers.lambdaQuery(FinanceBasePrice.class)
        .le(FinanceBasePrice::getPriceMonth, pricingMonth.trim())
        .eq(FinanceBasePrice::getPriceSource, priceSource.trim());
    if (hasFactorCode) {
      wrapper.eq(FinanceBasePrice::getFactorCode, factorCode.trim());
    } else {
      wrapper.eq(FinanceBasePrice::getShortName, shortName.trim());
    }
    if (buScoped) {
      wrapper.eq(FinanceBasePrice::getBusinessUnitType, bu);
    }
    wrapper.orderByDesc(FinanceBasePrice::getPriceMonth)
        .orderByDesc(FinanceBasePrice::getId)
        .last("LIMIT 1");

    FinanceBasePrice row = mapper.selectOne(wrapper);
    if (row == null) {
      log.info("FINANCE 查询 {} 未命中: factorCode={}, shortName={}, priceSource={}, "
              + "month<={}, buScoped={}, bu={} —— 请确认财务至少导入过一条正式价",
          debugCode, factorCode, shortName, priceSource, pricingMonth, buScoped, bu);
    }
    return Optional.ofNullable(row);
  }
}
