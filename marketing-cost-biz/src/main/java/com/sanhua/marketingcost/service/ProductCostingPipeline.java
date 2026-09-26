package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;

/** 整单和单品唯一共用的产品级核算流水线。 */
public interface ProductCostingPipeline {

  ProductCostingResult execute(ProductCostingRequest request);

  /** 检查并准备 BOM、价格和技术资料；不生成成本版本，不提交 OA。 */
  ProductCostingResult prepare(ProductCostingRequest request);
}
