package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import java.util.List;

public interface ThreeExpenseRateService {
  List<ThreeExpenseRate> list(String department);

  ThreeExpenseRate create(ThreeExpenseRateRequest request);

  ThreeExpenseRate update(Long id, ThreeExpenseRateRequest request);

  boolean delete(Long id);

  List<ThreeExpenseRate> importItems(ThreeExpenseRateImportRequest request);
}
