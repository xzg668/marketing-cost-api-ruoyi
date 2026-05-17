package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefPageResponse;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefQueryService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CmsMaterialScrapRefQueryServiceImpl implements CmsMaterialScrapRefQueryService {
  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 200;

  private final MaterialScrapRefMapper materialScrapRefMapper;

  public CmsMaterialScrapRefQueryServiceImpl(MaterialScrapRefMapper materialScrapRefMapper) {
    this.materialScrapRefMapper = materialScrapRefMapper;
  }

  @Override
  public CmsMaterialScrapRefPageResponse<MaterialScrapRef> pageCurrent(
      String materialCode,
      String scrapCode,
      String keyword,
      int current,
      int size,
      String businessUnitType) {
    QueryWrapper<MaterialScrapRef> query =
        currentQuery(materialCode, scrapCode, businessUnitType)
            .and(
                StringUtils.hasText(keyword),
                wrapper ->
                    wrapper
                        .like("material_name", trim(keyword))
                        .or()
                        .like("scrap_name", trim(keyword)))
            .orderByAsc("material_code")
            .orderByAsc("scrap_code")
            .orderByDesc("id");
    Page<MaterialScrapRef> page =
        materialScrapRefMapper.selectPage(new Page<>(pageNo(current), pageSize(size)), query);
    return new CmsMaterialScrapRefPageResponse<>(page.getTotal(), page.getRecords());
  }

  private QueryWrapper<MaterialScrapRef> currentQuery(
      String materialCode, String scrapCode, String businessUnitType) {
    return new QueryWrapper<MaterialScrapRef>()
        .like(StringUtils.hasText(materialCode), "material_code", normalized(materialCode))
        .like(StringUtils.hasText(scrapCode), "scrap_code", normalized(scrapCode))
        .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType));
  }

  private int pageNo(int current) {
    return current <= 0 ? DEFAULT_PAGE : current;
  }

  private int pageSize(int size) {
    if (size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  private String normalized(String value) {
    return CmsFieldNormalizeUtils.normalize(value);
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }
}
