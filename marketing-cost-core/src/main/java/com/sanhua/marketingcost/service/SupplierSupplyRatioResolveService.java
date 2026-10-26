package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import java.time.LocalDate;

public interface SupplierSupplyRatioResolveService {

  java.util.Map<String, SupplierSupplyRatioResolveResult> resolveAll(
      String businessUnitType, java.util.Set<String> materialCodes, LocalDate pricingDate);

  /** Same primary-supplier policy over a single captured input set; performs no query. */
  default java.util.Map<String, SupplierSupplyRatioResolveResult> resolveAll(
      String businessUnitType, java.util.Set<String> materialCodes, LocalDate pricingDate,
      java.util.Map<String, ? extends java.util.Collection<? extends com.sanhua.marketingcost.entity.SupplierSupplyRatio>> capturedRows) {
    return com.sanhua.marketingcost.service.pricing.SupplierPrimaryRatioPolicy.resolve(
        businessUnitType, materialCodes, pricingDate, capturedRows);
  }

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
