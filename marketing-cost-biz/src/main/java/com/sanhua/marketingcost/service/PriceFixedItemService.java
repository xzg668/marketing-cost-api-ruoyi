package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import java.util.List;

public interface PriceFixedItemService {
  /**
   * 分页查询。
   *
   * @param sourceType    V46 新增：按来源类型筛选（PURCHASE/MAKE/SETTLE/SCRAP），null=全部
   * @param pricingMonth  V46 新增：按结算期间筛选（YYYY-MM），null=全部
   */
  Page<PriceFixedItem> page(String materialCode, String supplierCode, String sourceType,
      String pricingMonth, int page, int pageSize);

  PriceFixedItem create(PriceFixedItemUpdateRequest request);

  PriceFixedItem update(Long id, PriceFixedItemUpdateRequest request);

  boolean delete(Long id);

  List<PriceFixedItem> importItems(PriceFixedItemImportRequest request);
}
