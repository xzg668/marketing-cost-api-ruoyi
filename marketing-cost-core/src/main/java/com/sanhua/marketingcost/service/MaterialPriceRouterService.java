package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceTypeRoute;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Collection;

/** 按月份、组织、料号的财务顺序排列适用来源；价格有效性由各价格源核验。 */
public interface MaterialPriceRouterService {
  Map<String, List<PriceTypeRoute>> listCandidatesBatch(Collection<String> materialCodes,
      String period, LocalDate quoteDate, String priceOrgCode, String businessUnitType);

  Map<String, List<PriceTypeRoute>> prepareCandidatesBatch(Map<String, String> materialNames,
      String period, LocalDate quoteDate, String priceOrgCode, String businessUnitType);

  default Optional<PriceTypeRoute> resolve(String materialCode, String period, LocalDate quoteDate,
      String priceOrgCode, String businessUnitType) {
    return listCandidates(materialCode, period, quoteDate, priceOrgCode, businessUnitType).stream().findFirst();
  }

  /** 只读预览和已准备价格的消费，不生成配置。 */
  List<PriceTypeRoute> listCandidates(String materialCode, String period, LocalDate quoteDate,
      String priceOrgCode, String businessUnitType);

  /** 真实价格准备入口：首次生成该范围的默认顺序，已有设置保持不变。 */
  List<PriceTypeRoute> prepareCandidates(String materialCode, String materialName, String period,
      LocalDate quoteDate, String priceOrgCode, String businessUnitType);
}
