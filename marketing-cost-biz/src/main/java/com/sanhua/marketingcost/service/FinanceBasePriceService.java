package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FinanceBasePriceImportRequest;
import com.sanhua.marketingcost.dto.FinanceBasePriceRequest;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import java.util.List;

public interface FinanceBasePriceService {
  List<FinanceBasePrice> list(String priceMonth, String keyword);

  FinanceBasePrice create(FinanceBasePriceRequest request);

  FinanceBasePrice update(Long id, FinanceBasePriceRequest request);

  boolean delete(Long id);

  List<FinanceBasePrice> importPrices(FinanceBasePriceImportRequest request);
}
