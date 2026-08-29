package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateImportResponse;
import com.sanhua.marketingcost.dto.QualityLossRateRequest;
import com.sanhua.marketingcost.entity.QualityLossRate;

public interface QualityLossRateService {
  Page<QualityLossRate> page(
      String productCategory,
      String productSubcategory,
      Integer rateYear,
      String businessDivision,
      String bareProductCode,
      String productName,
      String productModel,
      int page,
      int pageSize);

  QualityLossRate create(QualityLossRateRequest request);

  QualityLossRate update(Long id, QualityLossRateRequest request);

  boolean delete(Long id);

  QualityLossRateImportResponse importItems(QualityLossRateImportRequest request);
}
