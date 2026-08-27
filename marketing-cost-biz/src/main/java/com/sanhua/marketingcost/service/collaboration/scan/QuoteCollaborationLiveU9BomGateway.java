package com.sanhua.marketingcost.service.collaboration.scan;

/** 只读取此刻 U9 正式数据；月度冻结由外层网关统一处理。 */
public interface QuoteCollaborationLiveU9BomGateway {

  CurrentU9BomResult readLive(QuoteCollaborationScanContext context);
}
