package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import java.time.LocalDate;

public interface SupplierSupplyRatioResolveService {

  SupplierSupplyRatioResolveResult resolve(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate);

  SupplierSupplyRatioResolveResult resolveByMonth(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      String pricingMonth);
}
