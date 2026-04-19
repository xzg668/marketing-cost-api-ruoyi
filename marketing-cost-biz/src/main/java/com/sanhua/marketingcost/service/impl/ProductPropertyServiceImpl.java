package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ProductPropertyImportRequest;
import com.sanhua.marketingcost.dto.ProductPropertyRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.service.ProductPropertyService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductPropertyServiceImpl implements ProductPropertyService {
  private final ProductPropertyMapper productPropertyMapper;

  public ProductPropertyServiceImpl(ProductPropertyMapper productPropertyMapper) {
    this.productPropertyMapper = productPropertyMapper;
  }

  @Override
  public List<ProductProperty> list(String level1Name, String parentCode) {
    var query = Wrappers.lambdaQuery(ProductProperty.class);
    if (StringUtils.hasText(level1Name)) {
      query.like(ProductProperty::getLevel1Name, level1Name.trim());
    }
    if (StringUtils.hasText(parentCode)) {
      query.like(ProductProperty::getParentCode, parentCode.trim());
    }
    query.orderByDesc(ProductProperty::getPeriod).orderByDesc(ProductProperty::getId);
    return productPropertyMapper.selectList(query);
  }

  @Override
  public ProductProperty create(ProductPropertyRequest request) {
    if (request == null) {
      return null;
    }
    ProductProperty entity = new ProductProperty();
    merge(entity, request);
    fillDefaults(entity);
    if (!hasRequired(entity)) {
      return null;
    }
    productPropertyMapper.insert(entity);
    return entity;
  }

  @Override
  public ProductProperty update(Long id, ProductPropertyRequest request) {
    if (id == null) {
      return null;
    }
    ProductProperty existing = productPropertyMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    productPropertyMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && productPropertyMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<ProductProperty> importItems(ProductPropertyImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<ProductProperty> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null) {
        continue;
      }
      ProductProperty entity = new ProductProperty();
      fillFromRow(entity, row);
      fillDefaults(entity);
      if (!hasRequired(entity)) {
        continue;
      }
      ProductProperty existing = findExisting(entity);
      if (existing == null) {
        productPropertyMapper.insert(entity);
        imported.add(entity);
      } else {
        merge(existing, entity);
        productPropertyMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  private void fillFromRow(ProductProperty entity, ProductPropertyImportRequest.ProductPropertyRow row) {
    entity.setLevel1Code(row.getLevel1Code());
    entity.setLevel1Name(row.getLevel1Name());
    entity.setParentCode(row.getParentCode());
    entity.setParentName(row.getParentName());
    entity.setParentSpec(row.getParentSpec());
    entity.setParentModel(row.getParentModel());
    entity.setPeriod(row.getPeriod());
    entity.setProductAttr(row.getProductAttr());
  }

  private void merge(ProductProperty target, ProductProperty source) {
    if (source.getLevel1Code() != null) {
      target.setLevel1Code(source.getLevel1Code());
    }
    if (source.getLevel1Name() != null) {
      target.setLevel1Name(source.getLevel1Name());
    }
    if (source.getParentCode() != null) {
      target.setParentCode(source.getParentCode());
    }
    if (source.getParentName() != null) {
      target.setParentName(source.getParentName());
    }
    if (source.getParentSpec() != null) {
      target.setParentSpec(source.getParentSpec());
    }
    if (source.getParentModel() != null) {
      target.setParentModel(source.getParentModel());
    }
    if (source.getPeriod() != null) {
      target.setPeriod(source.getPeriod());
    }
    if (source.getProductAttr() != null) {
      target.setProductAttr(source.getProductAttr());
    }
  }

  private void merge(ProductProperty entity, ProductPropertyRequest request) {
    if (request == null) {
      return;
    }
    if (request.getLevel1Code() != null) {
      entity.setLevel1Code(request.getLevel1Code());
    }
    if (request.getLevel1Name() != null) {
      entity.setLevel1Name(request.getLevel1Name());
    }
    if (request.getParentCode() != null) {
      entity.setParentCode(request.getParentCode());
    }
    if (request.getParentName() != null) {
      entity.setParentName(request.getParentName());
    }
    if (request.getParentSpec() != null) {
      entity.setParentSpec(request.getParentSpec());
    }
    if (request.getParentModel() != null) {
      entity.setParentModel(request.getParentModel());
    }
    if (request.getPeriod() != null) {
      entity.setPeriod(request.getPeriod());
    }
    if (request.getProductAttr() != null) {
      entity.setProductAttr(request.getProductAttr());
    }
  }

  private void fillDefaults(ProductProperty entity) {
    entity.setLevel1Code(trimToNull(entity.getLevel1Code()));
    entity.setLevel1Name(trimToNull(entity.getLevel1Name()));
    entity.setParentCode(trimToNull(entity.getParentCode()));
    entity.setParentName(trimToNull(entity.getParentName()));
    entity.setParentSpec(trimToNull(entity.getParentSpec()));
    entity.setParentModel(trimToNull(entity.getParentModel()));
    entity.setPeriod(trimToNull(entity.getPeriod()));
    entity.setProductAttr(trimToNull(entity.getProductAttr()));
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private boolean hasRequired(ProductProperty entity) {
    return StringUtils.hasText(entity.getLevel1Code())
        && StringUtils.hasText(entity.getLevel1Name())
        && StringUtils.hasText(entity.getParentCode())
        && StringUtils.hasText(entity.getPeriod())
        && StringUtils.hasText(entity.getProductAttr());
  }

  private ProductProperty findExisting(ProductProperty entity) {
    var query = Wrappers.lambdaQuery(ProductProperty.class)
        .eq(ProductProperty::getLevel1Code, entity.getLevel1Code())
        .eq(ProductProperty::getParentCode, entity.getParentCode());
    if (StringUtils.hasText(entity.getPeriod())) {
      query.eq(ProductProperty::getPeriod, entity.getPeriod());
    } else {
      query.isNull(ProductProperty::getPeriod);
    }
    return productPropertyMapper.selectOne(query.last("LIMIT 1"));
  }
}
