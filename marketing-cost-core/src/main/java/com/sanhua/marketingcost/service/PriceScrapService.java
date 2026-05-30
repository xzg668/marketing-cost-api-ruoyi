package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.PriceScrap;
import java.util.Collection;
import java.util.Map;

/** 废料回收价内部查询 service；旧页面维护接口已在 MPPG-08 删除。 */
public interface PriceScrapService {

  /** 按 CMS 回收料号取当前废料价；不按 pricingMonth、报价期间、CMS 期间过滤。 */
  PriceScrap getCurrentByScrapCode(String scrapCode);

  /** 批量取当前废料价，返回 key 为标准化后的 CMS 回收料号。 */
  Map<String, PriceScrap> getCurrentByScrapCodes(Collection<String> scrapCodes);
}
