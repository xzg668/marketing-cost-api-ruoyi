package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import java.util.*;

/** 版本号覆盖所有已保存状态；未采用的暂存版本也不能被新版本占用。调用方持有财务目录锁。 */
public final class OriginalRuleRevisions {
  private OriginalRuleRevisions() {}
  public static Map<String, Integer> linked(PriceLinkedItemMapper mapper, String unit, String month) {
    Map<String, Integer> result = new HashMap<>();
    for (var row : mapper.selectList(Wrappers.lambdaQuery(PriceLinkedItem.class)
        .select(PriceLinkedItem::getRuleIdentityKey, PriceLinkedItem::getRuleRevision)
        .eq(PriceLinkedItem::getBusinessUnitType, unit).eq(PriceLinkedItem::getPricingMonth, month)
        .isNotNull(PriceLinkedItem::getRuleIdentityKey)))
      result.merge(row.getRuleIdentityKey(), Objects.requireNonNullElse(row.getRuleRevision(), 0), Math::max);
    return result;
  }
  public static Map<String, Integer> ranges(PriceRangeFactorRuleMapper mapper, String unit, String month) {
    Map<String, Integer> result = new HashMap<>();
    for (var row : mapper.selectList(Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .select(PriceRangeFactorRule::getRuleIdentityKey, PriceRangeFactorRule::getVersionNo)
        .eq(PriceRangeFactorRule::getBusinessUnitType, unit).eq(PriceRangeFactorRule::getPricingMonth, month)
        .isNotNull(PriceRangeFactorRule::getRuleIdentityKey)))
      result.merge(row.getRuleIdentityKey(), Objects.requireNonNullElse(row.getVersionNo(), 0), Math::max);
    return result;
  }
}
