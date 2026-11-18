package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;

public interface MaterialPriceTypeService {
  default MaterialPriceTypePageResponse page(String materialCode, String period, String priceOrgCode, int page, int pageSize) {
    return page(materialCode, period, priceOrgCode, page, pageSize, null);
  }
  MaterialPriceTypePageResponse page(String materialCode, String period, String priceOrgCode, int page, int pageSize, String model);
  MaterialPriceTypePageResponse.Row update(Long id, MaterialPriceTypeRequest request);
}
