package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;

/** 标准模板和类型 2 模板的显式导入分流边界。 */
public interface PriceLinkedImportDispatchService {

  PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command);

  PriceItemImportResponse confirm(PriceLinkedImportCommand command);
}
