package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioChangePage;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioHistoryMapper;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioScope;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioServiceImpl implements SupplierSupplyRatioService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 200;

  private final SupplierSupplyRatioMapper mapper;

  private final SupplierSupplyRatioHistoryMapper history;

  public SupplierSupplyRatioServiceImpl(SupplierSupplyRatioMapper mapper, SupplierSupplyRatioHistoryMapper history) {
    this.mapper = mapper;
    this.history = history;
  }

  @Override
  public SupplierSupplyRatioPageResponse page(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
      String supplierCode,
      String sourceBatchNo,
      String sourceType,
      Integer isActive,
      int page,
      int pageSize,
      String businessUnitType) {
    if (isActive != null && isActive != 0 && isActive != 1) {
      throw new IllegalArgumentException("有效状态只能是0或1");
    }
    QueryWrapper<SupplierSupplyRatio> query = activeQuery(businessUnitType)
        .eq(isActive != null, "is_active", isActive)
        .like(StringUtils.hasText(materialCode), "material_code", trim(materialCode))
        .like(StringUtils.hasText(materialName), "material_name", trim(materialName))
        .like(StringUtils.hasText(specModel), "spec_model", trim(specModel))
        .like(StringUtils.hasText(supplierName), "supplier_name", trim(supplierName))
        .like(StringUtils.hasText(supplierCode), "supplier_code", trim(supplierCode))
        .eq(StringUtils.hasText(sourceBatchNo), "source_batch_no", trim(sourceBatchNo))
        .eq(StringUtils.hasText(sourceType), "source_type", trim(sourceType))
        .orderByAsc("material_code")
        .orderByAsc("material_name")
        .orderByAsc("spec_model")
        .orderByDesc("supply_ratio")
        .orderByDesc("updated_at")
        .orderByDesc("id");
    Page<SupplierSupplyRatio> result =
        mapper.selectPage(new Page<>(pageNo(page), pageSize(pageSize)), query);
    return new SupplierSupplyRatioPageResponse(result.getTotal(), result.getRecords());
  }

  @Override
  public SupplierSupplyRatioChangePage changes(String materialCode,String supplierCode,String batchNo,
      int page,int pageSize,String businessUnitType) {
    String unit=SupplierSupplyRatioScope.require(businessUnitType);
    int size=pageSize(pageSize);
    return new SupplierSupplyRatioChangePage(history.count(unit,trim(materialCode),trim(supplierCode),trim(batchNo)),
        history.list(unit,trim(materialCode),trim(supplierCode),trim(batchNo),size,((long)pageNo(page)-1)*size));
  }

  private QueryWrapper<SupplierSupplyRatio> activeQuery(String businessUnitType) {
    return new QueryWrapper<SupplierSupplyRatio>()
        .eq("deleted", 0)
        .eq("business_unit_type", SupplierSupplyRatioScope.require(businessUnitType));
  }

  private int pageNo(int page) {
    return page <= 0 ? DEFAULT_PAGE : page;
  }

  private int pageSize(int pageSize) {
    if (pageSize <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(pageSize, MAX_SIZE);
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

}
