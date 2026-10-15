package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;

public interface MaterialPriceTypeService {
  MaterialPriceTypePageResponse page(String materialCode, String period, String priceOrgCode, int page, int pageSize);
  MaterialPriceTypePageResponse.Row update(Long id, MaterialPriceTypeRequest request);
}
