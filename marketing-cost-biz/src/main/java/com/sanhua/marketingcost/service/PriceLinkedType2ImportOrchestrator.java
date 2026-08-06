package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;

/** 类型 2 只读预检和事务确认导入编排。 */
public interface PriceLinkedType2ImportOrchestrator {

  PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command);

  PriceItemImportResponse confirm(PriceLinkedImportCommand command);
}
