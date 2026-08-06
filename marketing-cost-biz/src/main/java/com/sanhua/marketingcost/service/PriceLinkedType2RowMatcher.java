package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;

/** 使用“料号 + 供应商名称”精确匹配类型 2 的两个 Sheet。 */
public interface PriceLinkedType2RowMatcher {

  PriceLinkedType2RowMatchSummary match(PriceLinkedType2WorkbookParseResult workbook);
}
