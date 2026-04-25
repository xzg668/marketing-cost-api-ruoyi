package com.sanhua.marketingcost.formula.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 财务基价查询 helper（Plan B T3）—— 统一 FINANCE_FACTOR 解析的四键精确查询。
 *
 * <p>背景：
 * 过去 {@code FactorVariableRegistryImpl.resolveFinanceFactor} 把 variable_name 当
 * short_name 查，且完全不过滤 price_source / business_unit_type，与 legacy 计算
 * 路径（factor_code + price_source + BU）完全对不上。本 helper 把"按 variable 元
 * 数据组装四键 SQL + 严格无兜底"的能力收敛为一处，供 T4 新版 registry 的
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
 * <p>严格无兜底：任何必填项缺失 → 返回 {@link Optional#empty()} 并打 WARN，
 * 绝不做"priceSource 模糊降级"或"月份回退"等隐式兜底，目的是把"财务未导入
 * 该月权威数据"暴露到前端提示而不是静默降级。
 */
@Component
public class FinanceBasePriceQuery {

  private static final Logger log = LoggerFactory.getLogger(FinanceBasePriceQuery.class);

  private final FinanceBasePriceMapper mapper;

  public FinanceBasePriceQuery(FinanceBasePriceMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 四键精确查询最新一条基价记录。
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
      log.warn("FINANCE 查询 {} 缺 pricingMonth（严格模式不做最新回退）", debugCode);
      return Optional.empty();
    }

    // 4) buScoped 开关下 bu 必填 —— 防止跨 BU 污染
    if (buScoped && (bu == null || bu.isBlank())) {
      log.warn("FINANCE 查询 {} buScoped=true 但当前请求无 businessUnitType", debugCode);
      return Optional.empty();
    }

    // 5) 组装 lambdaQuery；factorCode 优先过滤
    var wrapper = Wrappers.lambdaQuery(FinanceBasePrice.class)
        .eq(FinanceBasePrice::getPriceMonth, pricingMonth.trim())
        .eq(FinanceBasePrice::getPriceSource, priceSource.trim());
    if (hasFactorCode) {
      wrapper.eq(FinanceBasePrice::getFactorCode, factorCode.trim());
    } else {
      wrapper.eq(FinanceBasePrice::getShortName, shortName.trim());
    }
    if (buScoped) {
      wrapper.eq(FinanceBasePrice::getBusinessUnitType, bu);
    }
    // 同四键理论上只剩一条；加 id desc 以防并发导入留下重复行
    wrapper.orderByDesc(FinanceBasePrice::getId).last("LIMIT 1");

    FinanceBasePrice row = mapper.selectOne(wrapper);
    if (row == null) {
      log.info("FINANCE 查询 {} 未命中: factorCode={}, shortName={}, priceSource={}, "
              + "month={}, buScoped={}, bu={} —— 请确认财务已导入该月权威源数据",
          debugCode, factorCode, shortName, priceSource, pricingMonth, buScoped, bu);
    }
    return Optional.ofNullable(row);
  }
}
