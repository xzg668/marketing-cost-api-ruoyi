package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.DepartmentFundRateImportRequest;
import com.sanhua.marketingcost.dto.DepartmentFundRateRequest;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import java.util.List;

public interface DepartmentFundRateService {
  List<DepartmentFundRate> list(String businessUnit);

  DepartmentFundRate create(DepartmentFundRateRequest request);

  DepartmentFundRate update(Long id, DepartmentFundRateRequest request);

  boolean delete(Long id);

  List<DepartmentFundRate> importItems(DepartmentFundRateImportRequest request);
}
