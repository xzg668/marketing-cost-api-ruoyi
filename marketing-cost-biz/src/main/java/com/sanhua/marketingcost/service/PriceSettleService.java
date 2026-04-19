package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceSettleDetailResponse;
import com.sanhua.marketingcost.dto.PriceSettleImportRequest;
import com.sanhua.marketingcost.dto.PriceSettleItemUpdateRequest;
import com.sanhua.marketingcost.dto.PriceSettleUpdateRequest;
import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.entity.PriceSettleItem;

public interface PriceSettleService {
  Page<PriceSettle> page(String buyer, String month, int page, int pageSize);

  PriceSettleDetailResponse detail(Long id);

  PriceSettle create(PriceSettleUpdateRequest request);

  PriceSettle update(Long id, PriceSettleUpdateRequest request);

  boolean delete(Long id);

  PriceSettleDetailResponse importSettle(PriceSettleImportRequest request);

  PriceSettleItem createItem(Long settleId, PriceSettleItemUpdateRequest request);

  PriceSettleItem updateItem(Long id, PriceSettleItemUpdateRequest request);

  boolean deleteItem(Long id);
}
