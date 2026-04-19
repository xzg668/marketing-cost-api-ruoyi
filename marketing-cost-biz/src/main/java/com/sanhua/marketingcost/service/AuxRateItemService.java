package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxRateItemImportRequest;
import com.sanhua.marketingcost.dto.AuxRateItemRequest;
import com.sanhua.marketingcost.entity.AuxRateItem;
import java.util.List;

public interface AuxRateItemService {
  Page<AuxRateItem> page(String materialCode, String period, int page, int pageSize);

  AuxRateItem create(AuxRateItemRequest request);

  AuxRateItem update(Long id, AuxRateItemRequest request);

  boolean delete(Long id);

  List<AuxRateItem> importItems(AuxRateItemImportRequest request);
}
