package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateRequest;
import com.sanhua.marketingcost.entity.QualityLossRate;
import java.util.List;

public interface QualityLossRateService {
  Page<QualityLossRate> page(
      String company,
      String businessUnit,
      String productCategory,
      String productSubcategory,
      String customer,
      String period,
      int page,
      int pageSize);

  QualityLossRate create(QualityLossRateRequest request);

  QualityLossRate update(Long id, QualityLossRateRequest request);

  boolean delete(Long id);

  List<QualityLossRate> importItems(QualityLossRateImportRequest request);
}
