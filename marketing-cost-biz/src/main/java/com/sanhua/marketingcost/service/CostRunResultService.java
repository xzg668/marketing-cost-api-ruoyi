package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunResultDto;

public interface CostRunResultService {
  CostRunResultDto getResult(String oaNo, String productCode);

  CostRunResultDto getResult(Long costRunVersionId);
}
