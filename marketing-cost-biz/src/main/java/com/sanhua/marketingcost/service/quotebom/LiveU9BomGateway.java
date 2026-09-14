package com.sanhua.marketingcost.service.quotebom;

/** 只读取此刻 U9 正式数据；月度冻结由外层网关统一处理。 */
public interface LiveU9BomGateway {

  CurrentU9BomResult readLive(QuoteBomReadContext context);
}
