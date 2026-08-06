package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 类型 2 统一身份解析的只读数据边界。
 *
 * <p>刻意不暴露新增、更新或删除方法，防止身份预检修改旧绑定和历史价格。
 */
public interface PriceLinkedType2FactorIdentityReadRepository {

  List<FactorIdentity> findActiveIdentities(String businessUnitType);

  List<FactorMonthlyPrice> findActiveMonthlyPrices(
      Collection<Long> factorIdentityIds, String priceMonth);

  Map<Long, Long> countActiveLegacyBindings(Collection<Long> factorIdentityIds);
}
