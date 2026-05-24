package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartScrapMappingServiceImpl implements MakePartScrapMappingService {

  private final MaterialScrapRefMapper materialScrapRefMapper;

  public MakePartScrapMappingServiceImpl(MaterialScrapRefMapper materialScrapRefMapper) {
    this.materialScrapRefMapper = materialScrapRefMapper;
  }

  @Override
  public List<MaterialScrapRef> listMappings(String childMaterialNo, String businessUnitType) {
    if (!StringUtils.hasText(childMaterialNo)) {
      return List.of();
    }
    var query =
        Wrappers.lambdaQuery(MaterialScrapRef.class)
            .eq(MaterialScrapRef::getMaterialCode, childMaterialNo.trim())
            .orderByAsc(MaterialScrapRef::getScrapCode)
            .orderByDesc(MaterialScrapRef::getId);
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(MaterialScrapRef::getBusinessUnitType, businessUnitType.trim());
    }
    List<MaterialScrapRef> rows = materialScrapRefMapper.selectList(query);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    Map<String, MaterialScrapRef> distinct = new LinkedHashMap<>();
    for (MaterialScrapRef row : rows) {
      String scrapCode = trim(row.getScrapCode());
      if (scrapCode != null) {
        distinct.putIfAbsent(scrapCode, row);
      }
    }
    return new ArrayList<>(distinct.values());
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
