package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import java.util.List;

public interface CostRunPartItemService {
  List<CostRunPartItemDto> listByOaNo(String oaNo);

  List<CostRunPartItemDto> listStoredByOaNo(String oaNo);
}
