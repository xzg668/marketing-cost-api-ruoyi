package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;

public interface CostRunResultService {
  CostRunResultDto getResult(String oaNo, String productCode);

  void saveOrUpdate(OaForm form, OaFormItem item);

  void updateTotalCost(String oaNo, String productCode, java.math.BigDecimal totalCost);
}
