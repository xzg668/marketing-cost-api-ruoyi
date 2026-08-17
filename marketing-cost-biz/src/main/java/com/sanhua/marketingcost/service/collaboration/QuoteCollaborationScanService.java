package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;

/** 新报价产品的只读协作判断入口。 */
public interface QuoteCollaborationScanService {

  QuoteCollaborationScanResult scanQuoteItem(Long oaFormItemId);
}
