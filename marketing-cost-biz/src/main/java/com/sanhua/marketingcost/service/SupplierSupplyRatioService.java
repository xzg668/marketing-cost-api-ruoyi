package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;

public interface SupplierSupplyRatioService {

  SupplierSupplyRatioPageResponse page(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
      String sourceType,
      int page,
      int pageSize,
      String businessUnitType);

  SupplierSupplyRatio update(Long id, SupplierSupplyRatioUpdateRequest request, String operator);

  void delete(Long id, String operator);
}
