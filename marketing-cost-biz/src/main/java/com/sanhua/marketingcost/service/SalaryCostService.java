package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SalaryCostImportRequest;
import com.sanhua.marketingcost.dto.SalaryCostRequest;
import com.sanhua.marketingcost.entity.SalaryCost;
import java.util.List;

public interface SalaryCostService {
  List<SalaryCost> list(String materialCode, String businessUnit);

  SalaryCost create(SalaryCostRequest request);

  SalaryCost update(Long id, SalaryCostRequest request);

  boolean delete(Long id);

  List<SalaryCost> importItems(SalaryCostImportRequest request);
}
