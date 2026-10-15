package com.sanhua.marketingcost.service.financefactor;

import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import org.springframework.stereotype.Service;

/** 技术月价没有组织维度；公共因素一经财务维护，其技术副本便不能再独立调价。 */
@Service
public class FinanceFactorAuthority {
  private final FactorIdentityMapper identities;
  private final FactorMonthlyPriceMapper prices;
  private final FactorCanonicalKeyService keys;

  public FinanceFactorAuthority(
      FactorIdentityMapper identities,
      FactorMonthlyPriceMapper prices,
      FactorCanonicalKeyService keys) {
    this.identities = identities;
    this.prices = prices;
    this.keys = keys;
  }

  public boolean manages(String businessUnit, String priceSource, String shortName) {
    String key = keys.build(priceSource, shortName);
    return key.endsWith("|1#CU")
        || (!key.isBlank() && prices.countFinanceManagedKey(businessUnit, key) > 0);
  }

  public boolean manages(FactorIdentity identity) {
    return manages(
        identity.getBusinessUnitType(), identity.getPriceSource(), identity.getShortName());
  }

  public void requirePrivate(Long identityId) {
    FactorIdentity identity = identityId == null ? null : identities.selectById(identityId);
    if (identity == null) throw new IllegalArgumentException("影响因素身份不存在：" + identityId);
    if (manages(identity))
      throw new IllegalArgumentException("公共因素请在财务基价中发布新修订：" + identity.getShortName());
  }
}
