package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioServiceImpl implements SupplierSupplyRatioService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 200;

  private final SupplierSupplyRatioMapper mapper;

  public SupplierSupplyRatioServiceImpl(SupplierSupplyRatioMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public SupplierSupplyRatioPageResponse page(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
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
  @Transactional(rollbackFor = Exception.class)
  public SupplierSupplyRatio update(
      Long id, SupplierSupplyRatioUpdateRequest request, String operator) {
    if (id == null) {
      throw new IllegalArgumentException("供货比例 id 不能为空");
    }
    if (request == null) {
      throw new IllegalArgumentException("更新内容不能为空");
    }
    if (request.getSupplyRatio() != null && request.getSupplyRatio().compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("供货比例不能小于 0");
    }
    if (request.getSupplyRatio() != null && request.getSupplyRatio().compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("供货比例不能大于 100%");
    }
    SupplierSupplyRatio existing = requireActive(id);

    // 普通维护保留原物料、供应商名称及导入批次。
    existing.setUnit(trimToNull(request.getUnit()));
    existing.setMaterialShape(trimToNull(request.getMaterialShape()));
    existing.setSupplierCode(trimToNull(request.getSupplierCode()));
    existing.setSupplyRatio(request.getSupplyRatio());
    existing.setEffectiveFrom(request.getEffectiveFrom());
    existing.setEffectiveTo(request.getEffectiveTo());
    existing.setUpdatedBy(currentOperator(operator));
    existing.setUpdatedAt(LocalDateTime.now());
    mapper.updateById(existing);
    return existing;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id, String operator) {
    SupplierSupplyRatio existing = requireActive(id);
    existing.setIsActive(0);
    existing.setUpdatedBy(currentOperator(operator));
    existing.setUpdatedAt(LocalDateTime.now());
    mapper.updateById(existing);
  }

  private SupplierSupplyRatio requireActive(Long id) {
    if (id == null) {
      throw new IllegalArgumentException("供货比例 id 不能为空");
    }
    SupplierSupplyRatio existing = mapper.selectOne(
        new QueryWrapper<SupplierSupplyRatio>().eq("id", id).eq("deleted", 0)
            .eq("is_active", 1).last("FOR UPDATE"));
    if (existing == null) {
      throw new IllegalArgumentException("供货比例不存在或已失效：id=" + id);
    }
    return existing;
  }

  private QueryWrapper<SupplierSupplyRatio> activeQuery(String businessUnitType) {
    return new QueryWrapper<SupplierSupplyRatio>()
        .eq("deleted", 0)
        .eq(StringUtils.hasText(businessUnitType), "business_unit_type", trim(businessUnitType));
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

  private String currentOperator(String operator) {
    return StringUtils.hasText(operator) ? operator.trim() : "system";
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  private String trimToNull(String value) {
    String trimmed = trim(value);
    return StringUtils.hasText(trimmed) ? trimmed : null;
  }
}
