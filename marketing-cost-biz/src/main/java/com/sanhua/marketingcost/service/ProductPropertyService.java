package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ProductPropertyImportRequest;
import com.sanhua.marketingcost.dto.ProductPropertyRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import java.io.InputStream;
import java.util.List;

public interface ProductPropertyService {
  List<ProductProperty> list(
      String level1Name,
      String parentCode,
      Integer propertyYear,
      String businessDivision,
      String productCode,
      String productName,
      String productAttr,
      String attrSourceType,
      String annualUsageSourceType);

  ProductProperty create(ProductPropertyRequest request);

  ProductProperty update(Long id, ProductPropertyRequest request);

  boolean delete(Long id);

  ProductPropertyAnnualSyncResult importItems(ProductPropertyImportRequest request);

  ProductPropertyAnnualSyncResult importExcel(
      InputStream input, Integer propertyYear, String businessUnitType);
}
