package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;

public interface PriceLinkedCalcService {
  Page<PriceLinkedCalcRow> page(String oaNo, String itemCode, String shapeAttr,
      int page, int pageSize);

  int refresh(String oaNo);
}
