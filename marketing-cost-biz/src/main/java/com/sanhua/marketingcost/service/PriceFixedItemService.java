package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import java.util.List;

public interface PriceFixedItemService {
  Page<PriceFixedItem> page(String materialCode, String supplierCode, int page, int pageSize);

  PriceFixedItem create(PriceFixedItemUpdateRequest request);

  PriceFixedItem update(Long id, PriceFixedItemUpdateRequest request);

  boolean delete(Long id);

  List<PriceFixedItem> importItems(PriceFixedItemImportRequest request);
}
