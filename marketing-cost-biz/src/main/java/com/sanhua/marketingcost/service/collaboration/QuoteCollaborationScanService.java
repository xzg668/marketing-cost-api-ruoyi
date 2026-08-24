package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;

/** 新报价产品的只读协作判断入口。 */
public interface QuoteCollaborationScanService {

  QuoteCollaborationScanResult scanQuoteItem(Long oaFormItemId);

  /** 核算流水线专用：使用本次请求月份，不退回 OA 表头的历史月份。 */
  QuoteCollaborationScanResult scanQuoteItem(Long oaFormItemId, String accountingMonth);
}
