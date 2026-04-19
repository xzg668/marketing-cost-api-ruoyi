package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;

import java.util.List;

public interface PriceLinkedItemService {
  List<PriceLinkedItemDto> list(String pricingMonth, String materialCode);

  PriceLinkedItemDto create(PriceLinkedItemUpdateRequest request);

  PriceLinkedItemDto update(Long id, PriceLinkedItemUpdateRequest request);

  List<PriceLinkedItemDto> importItems(PriceLinkedItemImportRequest request);

  boolean delete(Long id);
}
