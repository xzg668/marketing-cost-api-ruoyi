package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.ProductPropertyImportResult;
import com.sanhua.marketingcost.dto.ProductPropertyRuleSaveRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import java.io.InputStream;
import java.util.List;

public interface ProductPropertyService {
  Page<ProductProperty> page(
      Integer propertyYear,
      String businessDivision,
      String productCode,
      String productName,
      String productAttr,
      String businessUnitType,
      int page,
      int pageSize);

  ProductPropertyImportResult importExcel(
      InputStream input,
      String fileName,
      Integer propertyYear,
      String businessUnitType,
      String importMode);

  List<ProductPropertyRule> listRules(Integer propertyYear, String businessUnitType);

  List<ProductPropertyRule> saveRules(ProductPropertyRuleSaveRequest request);
}
