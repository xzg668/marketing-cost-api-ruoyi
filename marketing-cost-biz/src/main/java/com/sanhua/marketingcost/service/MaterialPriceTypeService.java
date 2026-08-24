package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import java.util.List;

public interface MaterialPriceTypeService {
  Page<MaterialPriceType> page(String billNo, String materialCode, String priceType, String period,
      int page, int pageSize);

  MaterialPriceType create(MaterialPriceTypeRequest request);

  MaterialPriceType update(Long id, MaterialPriceTypeRequest request);

  boolean delete(Long id);

  List<MaterialPriceType> importItems(MaterialPriceTypeImportRequest request);
}
