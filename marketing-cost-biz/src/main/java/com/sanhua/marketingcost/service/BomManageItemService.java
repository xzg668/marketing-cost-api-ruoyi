package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.entity.BomManageItem;
import java.util.List;

public interface BomManageItemService {
  Page<BomManageParentRow> page(String oaNo, String bomCode, String materialNo, String shapeAttr,
      int page, int pageSize);

  List<BomManageItem> listDetails(
      String oaNo, Long oaFormItemId, String bomCode, String rootItemCode, String shapeAttr);

  int refresh(BomManageRefreshRequest request);
}
