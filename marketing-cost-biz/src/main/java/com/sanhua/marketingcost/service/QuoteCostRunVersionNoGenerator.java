package com.sanhua.marketingcost.service;

public interface QuoteCostRunVersionNoGenerator {

  String nextCostRunNo();

  String nextVersionNo(Long oaFormItemId, String productCode);
}
