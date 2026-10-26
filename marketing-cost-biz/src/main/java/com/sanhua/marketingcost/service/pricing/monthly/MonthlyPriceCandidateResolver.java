package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.Set;

/** Source I/O happens before adoption locks; implementations return complete financial evidence. */
@FunctionalInterface
public interface MonthlyPriceCandidateResolver {
  MonthlyMaterialPrices.Candidates resolve(MonthlyMaterialPrices.Context context,Set<MonthlyPriceRequest> requests,
      java.util.function.Function<Set<MonthlyPriceRequest>,MonthlyMaterialPrices.Candidates> dependencies);
  default MonthlyMaterialPrices.Candidates resolveScrap(MonthlyMaterialPrices.Context context,Set<MonthlyPriceRequest> requests,
      java.util.function.Function<Set<MonthlyPriceRequest>,MonthlyMaterialPrices.Candidates> dependencies) {
    return resolve(context,requests,dependencies);
  }
}
