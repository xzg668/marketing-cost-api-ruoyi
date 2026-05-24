package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MaterialScrapRef;
import java.util.List;

public interface MakePartScrapMappingService {
  List<MaterialScrapRef> listMappings(String childMaterialNo, String businessUnitType);
}
