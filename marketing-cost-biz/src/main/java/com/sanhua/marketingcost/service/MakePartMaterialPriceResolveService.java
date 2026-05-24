package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import java.time.LocalDate;

public interface MakePartMaterialPriceResolveService {

  MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      String oaNo,
      String businessUnitType);
}
