package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 废料回收价 service (V48) */
public interface PriceScrapService {

  /** 分页查询：pricingMonth 仅用于页面筛选展示，不作为计算取价条件。 */
  Page<PriceScrap> page(String scrapCode, String pricingMonth, int page, int pageSize);

  /** 按 CMS 回收料号取当前废料价；不按 pricingMonth、报价期间、CMS 期间过滤。 */
  PriceScrap getCurrentByScrapCode(String scrapCode);

  /** 批量取当前废料价，返回 key 为标准化后的 CMS 回收料号。 */
  Map<String, PriceScrap> getCurrentByScrapCodes(Collection<String> scrapCodes);

  PriceScrap create(PriceScrapUpdateRequest request);

  PriceScrap update(Long id, PriceScrapUpdateRequest request);

  boolean delete(Long id);

  /** 批量导入：按 (scrap_code, BU) 去重 upsert，pricingMonth 只保留展示/来源含义。 */
  List<PriceScrap> importItems(PriceScrapImportRequest request);
}
