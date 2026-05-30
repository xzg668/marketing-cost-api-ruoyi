package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.service.MonthlyRepriceBatchService;
import com.sanhua.marketingcost.service.MonthlyRepriceLinkedPricePrepareService;
import com.sanhua.marketingcost.service.MonthlyRepriceObjectExpandService;
import com.sanhua.marketingcost.service.MonthlyRepriceStartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyRepriceStartServiceImpl implements MonthlyRepriceStartService {

  private final MonthlyRepriceBatchService batchService;
  private final MonthlyRepriceObjectExpandService objectExpandService;
  private final MonthlyRepriceLinkedPricePrepareService linkedPricePrepareService;

  public MonthlyRepriceStartServiceImpl(
      MonthlyRepriceBatchService batchService,
      MonthlyRepriceObjectExpandService objectExpandService,
      MonthlyRepriceLinkedPricePrepareService linkedPricePrepareService) {
    this.batchService = batchService;
    this.objectExpandService = objectExpandService;
    this.linkedPricePrepareService = linkedPricePrepareService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceBatchCreateResponse start(
      MonthlyRepriceBatchCreateRequest request, String operator) {
    MonthlyRepriceBatchCreateResponse response = batchService.createBatch(request, operator);
    MonthlyRepriceObjectExpandResult expandResult =
        objectExpandService.expand(response.getRepriceNo(), operator);
    response.setTotalCount(expandResult.getTotalCount());
    response.setSuccessCount(0);
    response.setFailedCount(0);
    response.setSkippedCount(expandResult.getSkippedCount());
    linkedPricePrepareService.prepare(response.getRepriceNo(), operator);
    return response;
  }
}
