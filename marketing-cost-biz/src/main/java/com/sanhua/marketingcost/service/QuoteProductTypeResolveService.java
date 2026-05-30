package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import java.util.Collection;
import java.util.List;

/** 统一报价产品裸品 / 非裸品识别服务。 */
public interface QuoteProductTypeResolveService {

  QuoteProductTypeResolveResult resolve(String quoteProductCode);

  /**
   * 批量识别报价产品形态。
   *
   * <p>返回列表和入参顺序一致，重复料号会复用同一次主档查询结果并按原顺序回填。
   */
  List<QuoteProductTypeResolveResult> batchResolve(Collection<String> quoteProductCodes);
}
