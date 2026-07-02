package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioCandidate;
import java.time.LocalDate;
import java.util.List;

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

  default SupplierSupplyRatioResolveResult resolveAmongSuppliers(
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate,
      List<SupplierSupplyRatioCandidate> candidates) {
    return resolve(businessUnitType, materialCode, materialName, specModel, pricingDate);
  }
}
