package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;

/**
 * 单桶取价 Resolver —— 每个 PriceTypeEnum 对应一个实现。
 *
 * <p>Service 注入所有实现，按 {@link #priceType()} 建索引；遇到对应桶时调用 resolve。
 */
public interface PriceResolver {

  /** 该 Resolver 支持的价格类型桶 */
  PriceTypeEnum priceType();

  /**
   * 取价。
   *
   * @param oaNo  当前试算单号（联动价 / BOM 计算等需要）
   * @param item  试算行（含 partCode、material 等上下文）
   * @param route Router 服务给出的命中记录（含 sourceSystem 等审计字段）
   * @return 取价结果；未命中请返回 {@link PriceResolveResult#miss(String)}，不要抛异常
   */
  PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route);
}
