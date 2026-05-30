package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportResponse;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import java.util.List;

public interface ThreeExpenseRateService {
  List<ThreeExpenseRate> list(
      String department,
      Integer periodYear,
      String productCategory,
      String productLine,
      String applicantDepartment,
      String applicantOffice);

  ThreeExpenseRate create(ThreeExpenseRateRequest request);

  ThreeExpenseRate update(Long id, ThreeExpenseRateRequest request);

  boolean delete(Long id);

  ThreeExpenseRateImportResponse importItems(ThreeExpenseRateImportRequest request);
}
