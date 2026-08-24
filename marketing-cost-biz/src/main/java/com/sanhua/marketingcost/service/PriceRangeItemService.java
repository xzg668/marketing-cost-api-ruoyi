package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import java.util.List;

public interface PriceRangeItemService {
  Page<PriceRangeItem> page(String materialCode, String supplierCode, String specModel,
      String effectiveFrom, int page, int pageSize);

  PriceRangeItem create(PriceRangeItemUpdateRequest request);

  PriceRangeItem update(Long id, PriceRangeItemUpdateRequest request);

  boolean delete(Long id);

  List<PriceRangeItem> importItems(PriceRangeItemImportRequest request);
}
