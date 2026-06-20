package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;

public interface CostRunTraceQueryService {

  CostRunTraceListResponse listByCostRunNo(String costRunNo);

  CostRunTraceDetailDto getByCostRunNoAndId(String costRunNo, Long traceId);
}
