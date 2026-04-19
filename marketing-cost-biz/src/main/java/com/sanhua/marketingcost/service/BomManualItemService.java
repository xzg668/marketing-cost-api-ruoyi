package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.BomManualItemImportRequest;
import com.sanhua.marketingcost.dto.BomManualItemRequest;
import com.sanhua.marketingcost.dto.BomManualSummaryRow;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.BomManualItem;
import java.util.List;

public interface BomManualItemService {
  List<BomManualItem> list(
      String bomCode, String itemCode, String parentCode, Integer bomLevel, String shapeAttr);

  Page<BomManualItem> page(String bomCode, String itemCode, String parentCode, Integer bomLevel,
      String shapeAttr, int page, int pageSize);

  Page<BomManualSummaryRow> summaryPage(String bomCode, String itemCode, String parentCode,
      Integer bomLevel, String shapeAttr, int page, int pageSize);

  List<BomManualItem> listByBomCode(String bomCode, String itemCode, String parentCode,
      Integer bomLevel, String shapeAttr);

  BomManualItem create(BomManualItemRequest request);

  BomManualItem update(Long id, BomManualItemRequest request);

  boolean delete(Long id);

  List<BomManualItem> importItems(BomManualItemImportRequest request);
}
