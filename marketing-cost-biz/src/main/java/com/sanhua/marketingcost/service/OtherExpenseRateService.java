package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.OtherExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.OtherExpenseRateRequest;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import java.util.List;

public interface OtherExpenseRateService {
  List<OtherExpenseRate> list(String materialCode, String productName);

  OtherExpenseRate create(OtherExpenseRateRequest request);

  OtherExpenseRate update(Long id, OtherExpenseRateRequest request);

  boolean delete(Long id);

  List<OtherExpenseRate> importItems(OtherExpenseRateImportRequest request);
}
