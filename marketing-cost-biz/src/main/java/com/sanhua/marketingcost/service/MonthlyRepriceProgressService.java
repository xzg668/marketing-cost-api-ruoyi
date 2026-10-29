package com.sanhua.marketingcost.service;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
/** Read-only historical progress. New contract transitions belong to MonthlyRepriceContractService. */
public interface MonthlyRepriceProgressService {
  MonthlyRepriceProgressSnapshot getProgress(String repriceNo);
}
