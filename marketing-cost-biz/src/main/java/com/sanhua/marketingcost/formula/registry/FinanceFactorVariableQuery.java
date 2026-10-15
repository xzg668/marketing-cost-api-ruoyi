package com.sanhua.marketingcost.formula.registry;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将变量词典和行内绑定的因素身份接到已发布财务方案，不再维护第二份公共基价。 */
@Component
public class FinanceFactorVariableQuery {
  private final FinanceFactorPriceReader finance;
  private final FactorIdentityMapper identities;
  private final FactorMonthlyPriceMapper prices;
  private final FactorCanonicalKeyService canonicalKeys;

  public FinanceFactorVariableQuery(
      FinanceFactorPriceReader finance,
      FactorIdentityMapper identities,
      FactorMonthlyPriceMapper prices,
      FactorCanonicalKeyService canonicalKeys) {
    this.finance = finance;
    this.identities = identities;
    this.prices = prices;
    this.canonicalKeys = canonicalKeys;
  }

  public Optional<Price> query(Map<String, Object> binding, VariableContext request) {
    var context = context(request);
    String key = key(binding, context.financial().scope().businessUnitType());
    if (!context.financial().factors().containsKey(key)) {
      // 某因素一旦由财务正式发布维护，当月方案缺行就必须报缺价，不能回到技术上传旧值。
      if (PriceFactorContext.COPPER.equals(key)
          || prices.countPublishedFactorKey(
                  context.financial().scope().businessUnitType(),
                  context.financial().scope().priceOrgCode(),
                  key)
              > 0) {
        throw new IllegalArgumentException("财务方案缺少因素：" + key);
      }
      return Optional.empty();
    }
    request.getUsedFinanceKeys().add(key);
    var factor = context.financial().required(key);
    String source = context.inputSource(key);
    if (PriceFactorContext.COPPER.equals(key) && request.getOverrideSource("Cu") != null)
      source = request.getOverrideSource("Cu");
    return Optional.of(
        new Price(
            context.requiredPrice(key, FactorPriceUnit.CNY_KG, true),
            context.months().sourceMonth(),
            factor.monthlyPriceId(),
            source,
            key));
  }

  /** 派生主材/废料必须在本次发布内唯一匹配；不按简称猜其他来源或未来月份。 */
  public Optional<BigDecimal> queryName(String name, VariableContext request) {
    if (!StringUtils.hasText(name)) return Optional.empty();
    var context = context(request);
    String normalized = canonicalKeys.build("AVG", name).substring(4);
    var matches =
        context.financial().factors().keySet().stream()
            .filter(key -> key.substring(key.indexOf('|') + 1).equals(normalized))
            .toList();
    if (matches.size() > 1) throw new IllegalArgumentException("财务因素存在多个价源，请显式绑定：" + name);
    if (!matches.isEmpty()) request.getUsedFinanceKeys().add(matches.getFirst());
    return matches.isEmpty()
        ? Optional.empty()
        : Optional.of(context.requiredPrice(matches.getFirst(), FactorPriceUnit.CNY_KG, true));
  }

  private String key(Map<String, Object> binding, String businessUnit) {
    Object identityId = binding.get("factorIdentityId");
    if (identityId != null) {
      var identity = identities.selectById(Long.valueOf(identityId.toString()));
      if (identity == null
          || !businessUnit.equals(identity.getBusinessUnitType())
          || !"ACTIVE".equals(identity.getStatus())) {
        throw new IllegalArgumentException("因素身份不存在、已停用或不属于当前业务单元：" + identityId);
      }
      String canonical =
          StringUtils.hasText(identity.getCanonicalFactorKey())
              ? canonicalKeys.normalizeExistingKey(identity.getCanonicalFactorKey())
              : canonicalKeys.build(identity.getPriceSource(), identity.getShortName());
      if (!StringUtils.hasText(canonical))
        throw new IllegalArgumentException("因素身份缺少简称或价源：" + identityId);
      return canonical;
    }
    String code = text(binding.get("factorCode"));
    String name = StringUtils.hasText(code) ? code : text(binding.get("shortName"));
    String source = text(binding.get("priceSource"));
    String key = canonicalKeys.build(source, name);
    if (!StringUtils.hasText(key)) throw new IllegalArgumentException("财务变量必须配置因素身份及价源");
    return key;
  }

  public PriceFactorContext context(VariableContext request) {
    var item = request == null ? null : request.getLinkedItem();
    if (item == null) throw new IllegalArgumentException("财务变量取价缺少规则及组织上下文");
    String business = item.getBusinessUnitType();
    String callerBusiness = BusinessUnitContext.getCurrentBusinessUnitType();
    if (StringUtils.hasText(callerBusiness) && !callerBusiness.equals(business))
      throw new IllegalArgumentException("规则与当前业务单元不一致");
    Condition condition =
        "220".equals(item.getOrgCode())
            ? (request.getOaForm() != null
                    && Boolean.TRUE.equals(request.getOaForm().getBoardTender())
                ? Condition.TENDER
                : Condition.NON_TENDER)
            : Condition.GENERAL;
    var context =
        finance
            .readEffective(
                new FinanceFactorImportContext(
                    business, item.getOrgCode(), request.getPricingMonth(), null, null),
                condition,
                item.getMaterialCode())
            .financialOnly();
    var form = request.getOaForm();
    if ("220".equals(item.getOrgCode())) {
      context =
          context.withBoardInputs(
              new com.sanhua.marketingcost.service.pricing.BoardPriceInputs(
                  form == null ? null : form.getSus304Price(),
                  form == null ? null : form.getSus316lPrice(),
                  form != null && Boolean.TRUE.equals(form.getBoardTender())));
    }
    if (!request.isMonthlyReprice() && !request.isFinanceQuoteBase()) {
      BigDecimal copper = request.getOverrides().get("Cu");
      if (copper != null)
        context = context.withCustomerCopperPerTon(copper.multiply(new BigDecimal("1000")));
      else if (form != null && form.getCopperPrice() != null)
        context = context.withCustomerCopperPerTon(form.getCopperPrice());
    }
    request.setPriceFactors(context);
    return context;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  public record Price(
      BigDecimal price,
      String sourceMonth,
      Long monthlyPriceId,
      String source,
      String canonicalKey) {
    public Price(BigDecimal price, String sourceMonth, Long monthlyPriceId, String source) {
      this(price, sourceMonth, monthlyPriceId, source, null);
    }
  }
}
