package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ProductPropertyImportRequest;
import com.sanhua.marketingcost.dto.ProductPropertyRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import java.util.List;

public interface ProductPropertyService {
  List<ProductProperty> list(String level1Name, String parentCode);

  ProductProperty create(ProductPropertyRequest request);

  ProductProperty update(Long id, ProductPropertyRequest request);

  boolean delete(Long id);

  List<ProductProperty> importItems(ProductPropertyImportRequest request);
}
