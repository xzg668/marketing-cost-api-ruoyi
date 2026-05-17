package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustmentResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceChangeLogDto;
import java.util.List;

public interface FactorMonthlyPriceAdjustmentService {
  FactorMonthlyPriceAdjustmentResponse adjust(
      Long factorMonthlyPriceId, FactorMonthlyPriceAdjustRequest request, String operator);

  List<FactorMonthlyPriceChangeLogDto> listChangeLogs(Long factorMonthlyPriceId);
}
