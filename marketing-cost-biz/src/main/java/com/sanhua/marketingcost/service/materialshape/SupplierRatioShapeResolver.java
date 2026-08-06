package com.sanhua.marketingcost.service.materialshape;

import java.util.List;
import java.util.Map;

/** 将 QEB-04 命中的 SUPPLIER_RATIO 规则解析为唯一主供应商和最终报价形态。 */
public interface SupplierRatioShapeResolver {

  SupplierRatioResolution resolve(
      MaterialQuoteShapeResolution policyResolution);

  /** 批量解析命中供货比例规则的料号；实现必须避免按料号逐条查询供货比例表。 */
  Map<String, SupplierRatioResolution> resolveAll(
      List<MaterialQuoteShapeResolution> policyResolutions);
}
