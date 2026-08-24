package com.sanhua.marketingcost.service;

/** 单产品报价成本运行版本失效服务，只允许把未完成 TRIAL/RUNNING 标记为 STALE。 */
public interface QuoteCostRunVersionInvalidationService {

  int invalidateByFinanceCu(String pricingMonth, String businessUnitType);

  int invalidateByOaCu(String oaNo);

  int invalidateProduct(
      String oaNo, Long oaFormItemId, String productCode, String pricingMonth);

  /**
   * BOM 分支发生业务变化时，使当前产品的试算和已确认版本都失效。
   *
   * <p>只转为 STALE，保留全部历史版本和结果用于审计。
   */
  int invalidateProductAfterBomChange(
      String oaNo, Long oaFormItemId, String productCode, String pricingMonth);
}
