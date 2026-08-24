package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;

/** 整单和单品唯一共用的产品级核算流水线。 */
public interface ProductCostingPipeline {

  ProductCostingResult execute(ProductCostingRequest request);
}
