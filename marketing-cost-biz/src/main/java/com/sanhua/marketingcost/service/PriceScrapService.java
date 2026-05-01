package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import java.util.List;

/** 废料回收价 service (V48) */
public interface PriceScrapService {

  /** 分页查询：可按废料代号 / 结算期间过滤 */
  Page<PriceScrap> page(String scrapCode, String pricingMonth, int page, int pageSize);

  PriceScrap create(PriceScrapUpdateRequest request);

  PriceScrap update(Long id, PriceScrapUpdateRequest request);

  boolean delete(Long id);

  /** 批量导入：按 (pricing_month, scrap_code, BU) 去重 upsert */
  List<PriceScrap> importItems(PriceScrapImportRequest request);
}
