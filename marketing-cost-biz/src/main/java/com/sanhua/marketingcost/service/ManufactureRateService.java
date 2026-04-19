package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ManufactureRateImportRequest;
import com.sanhua.marketingcost.dto.ManufactureRateRequest;
import com.sanhua.marketingcost.entity.ManufactureRate;
import java.util.List;

public interface ManufactureRateService {
  List<ManufactureRate> list(String businessUnit);

  ManufactureRate create(ManufactureRateRequest request);

  ManufactureRate update(Long id, ManufactureRateRequest request);

  boolean delete(Long id);

  List<ManufactureRate> importItems(ManufactureRateImportRequest request);
}
