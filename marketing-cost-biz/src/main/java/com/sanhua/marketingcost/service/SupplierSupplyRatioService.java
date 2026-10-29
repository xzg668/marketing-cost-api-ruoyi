package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioChangePage;

public interface SupplierSupplyRatioService {

  SupplierSupplyRatioPageResponse page(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
      String supplierCode,
      String sourceBatchNo,
      String sourceType,
      Integer isActive,
      int page,
      int pageSize,
      String businessUnitType);

  SupplierSupplyRatioChangePage changes(String materialCode, String supplierCode, String batchNo,
      int page, int pageSize, String businessUnitType);
}
