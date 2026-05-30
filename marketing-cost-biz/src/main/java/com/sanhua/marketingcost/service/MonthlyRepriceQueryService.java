package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceActiveLockDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePageResponse;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskQueryRequest;
import java.util.List;

public interface MonthlyRepriceQueryService {

  MonthlyRepricePageResponse<MonthlyRepriceBatchDto> pageBatches(
      MonthlyRepriceBatchQueryRequest request);

  MonthlyRepriceBatchDto getBatch(String repriceNo);

  MonthlyRepricePageResponse<MonthlyRepriceTaskDto> pageTasks(
      String repriceNo, MonthlyRepriceTaskQueryRequest request);

  MonthlyRepricePageResponse<MonthlyRepriceResultDto> pageResults(
      String repriceNo, MonthlyRepriceResultQueryRequest request);

  List<MonthlyRepricePartItemDto> listPartItems(String repriceNo, Long resultId);

  List<MonthlyRepriceCostItemDto> listCostItems(String repriceNo, Long resultId);

  MonthlyRepricePageResponse<MonthlyRepriceAuditLogDto> pageAuditLogs(
      MonthlyRepriceAuditLogQueryRequest request);

  MonthlyRepriceActiveLockDto getActiveLock();
}
