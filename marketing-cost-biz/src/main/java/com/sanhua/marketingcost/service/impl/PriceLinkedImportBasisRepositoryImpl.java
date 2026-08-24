package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisRepository;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class PriceLinkedImportBasisRepositoryImpl
    implements PriceLinkedImportBasisRepository {

  private final PriceLinkedItemMapper itemMapper;
  private final PriceVariableBindingMapper bindingMapper;
  private final FactorUploadBatchMapper batchMapper;

  public PriceLinkedImportBasisRepositoryImpl(
      PriceLinkedItemMapper itemMapper,
      PriceVariableBindingMapper bindingMapper,
      FactorUploadBatchMapper batchMapper) {
    this.itemMapper = itemMapper;
    this.bindingMapper = bindingMapper;
    this.batchMapper = batchMapper;
  }

  @Override
  public PriceLinkedItem findCurrentVersion(PriceLinkedItem identity) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getPricingMonth, identity.getPricingMonth())
        .eq(PriceLinkedItem::getMaterialCode, identity.getMaterialCode())
        .eq(PriceLinkedItem::getBusinessUnitType, identity.getBusinessUnitType())
        .eq(PriceLinkedItem::getDeleted, 0)
        .orderByDesc(PriceLinkedItem::getCreatedAt)
        .orderByDesc(PriceLinkedItem::getId);
    List<PriceLinkedItem> rows = itemMapper.selectList(query);
    String supplierKey = supplierKey(identity);
    return rows.stream()
        .filter(row -> supplierKey.equals(supplierKey(row)))
        .findFirst()
        .orElse(null);
  }

  private String supplierKey(PriceLinkedItem item) {
    String supplierCode = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
        item == null ? null : item.getSupplierCode());
    if (StringUtils.hasText(supplierCode)) {
      return "CODE:" + supplierCode;
    }
    String supplierName = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
        item == null ? null : item.getSupplierName());
    return StringUtils.hasText(supplierName) ? "NAME:" + supplierName : "NO_SUPPLIER";
  }

  @Override
  public PriceLinkedItem findById(Long id) {
    return itemMapper.selectById(id);
  }

  @Override
  public FactorUploadBatch findUploadBatchById(Long id) {
    return id == null ? null : batchMapper.selectById(id);
  }

  @Override
  public void insertItem(PriceLinkedItem item) {
    itemMapper.insert(item);
  }

  @Override
  public void updateItem(PriceLinkedItem item) {
    itemMapper.updateById(item);
  }

  @Override
  public void insertBinding(PriceVariableBinding binding) {
    bindingMapper.insert(binding);
  }

  @Override
  public List<PriceVariableBinding> findBindings(Long linkedItemId) {
    return bindingMapper.selectList(
        Wrappers.lambdaQuery(PriceVariableBinding.class)
            .eq(PriceVariableBinding::getLinkedItemId, linkedItemId)
            .orderByAsc(PriceVariableBinding::getId));
  }
}
