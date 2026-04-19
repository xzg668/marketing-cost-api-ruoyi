package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceSettleDetailResponse;
import com.sanhua.marketingcost.dto.PriceSettleImportRequest;
import com.sanhua.marketingcost.dto.PriceSettleItemUpdateRequest;
import com.sanhua.marketingcost.dto.PriceSettleUpdateRequest;
import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.entity.PriceSettleItem;
import com.sanhua.marketingcost.mapper.PriceSettleItemMapper;
import com.sanhua.marketingcost.mapper.PriceSettleMapper;
import com.sanhua.marketingcost.service.PriceSettleService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceSettleServiceImpl implements PriceSettleService {
  private final PriceSettleMapper settleMapper;
  private final PriceSettleItemMapper itemMapper;

  public PriceSettleServiceImpl(PriceSettleMapper settleMapper, PriceSettleItemMapper itemMapper) {
    this.settleMapper = settleMapper;
    this.itemMapper = itemMapper;
  }

  @Override
  public Page<PriceSettle> page(String buyer, String month, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(PriceSettle.class);
    if (StringUtils.hasText(buyer)) {
      query.like(PriceSettle::getBuyer, buyer.trim());
    }
    if (StringUtils.hasText(month)) {
      query.eq(PriceSettle::getMonth, month.trim());
    }
    query.orderByDesc(PriceSettle::getId);
    return settleMapper.selectPage(new Page<>(page, pageSize), query);
  }

  @Override
  public PriceSettleDetailResponse detail(Long id) {
    PriceSettle settle = settleMapper.selectById(id);
    if (settle == null) return null;
    List<PriceSettleItem> items = itemMapper.selectList(
        Wrappers.lambdaQuery(PriceSettleItem.class)
            .eq(PriceSettleItem::getSettleId, id)
            .orderByAsc(PriceSettleItem::getId));
    return new PriceSettleDetailResponse(settle, items);
  }

  @Override
  public PriceSettle create(PriceSettleUpdateRequest request) {
    if (request == null) return null;
    PriceSettle settle = new PriceSettle();
    mergeSettle(settle, request);
    settleMapper.insert(settle);
    return settle;
  }

  @Override
  public PriceSettle update(Long id, PriceSettleUpdateRequest request) {
    if (id == null) return null;
    PriceSettle existing = settleMapper.selectById(id);
    if (existing == null) return null;
    mergeSettle(existing, request);
    settleMapper.updateById(existing);
    return existing;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean delete(Long id) {
    if (id == null) return false;
    // 先删明细再删主表
    itemMapper.delete(Wrappers.lambdaQuery(PriceSettleItem.class)
        .eq(PriceSettleItem::getSettleId, id));
    return settleMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceSettleDetailResponse importSettle(PriceSettleImportRequest request) {
    if (request == null) return null;
    // 查找或创建主表
    PriceSettle settle = findExistingSettle(request);
    if (settle == null) {
      settle = new PriceSettle();
      settle.setBuyer(request.getBuyer());
      settle.setSeller(request.getSeller());
      settle.setBusinessType(request.getBusinessType());
      settle.setProductProperty(request.getProductProperty());
      settle.setCopperPrice(request.getCopperPrice());
      settle.setMonth(request.getMonth());
      settle.setApprovalContent(request.getApprovalContent());
      settleMapper.insert(settle);
    } else {
      settle.setSeller(request.getSeller());
      settle.setBusinessType(request.getBusinessType());
      settle.setProductProperty(request.getProductProperty());
      settle.setCopperPrice(request.getCopperPrice());
      settle.setApprovalContent(request.getApprovalContent());
      settleMapper.updateById(settle);
    }
    // 导入明细
    List<PriceSettleItem> items = new ArrayList<>();
    if (request.getItems() != null) {
      for (var row : request.getItems()) {
        if (row == null || !StringUtils.hasText(row.getMaterialCode())) continue;
        PriceSettleItem item = findExistingItem(settle.getId(), row.getMaterialCode().trim());
        if (item == null) {
          item = new PriceSettleItem();
          item.setSettleId(settle.getId());
          fillItem(item, row);
          itemMapper.insert(item);
        } else {
          fillItem(item, row);
          itemMapper.updateById(item);
        }
        items.add(item);
      }
    }
    return new PriceSettleDetailResponse(settle, items);
  }

  @Override
  public PriceSettleItem createItem(Long settleId, PriceSettleItemUpdateRequest request) {
    if (settleId == null || request == null) return null;
    if (settleMapper.selectById(settleId) == null) return null;
    if (!StringUtils.hasText(request.getMaterialCode())) return null;
    PriceSettleItem item = new PriceSettleItem();
    item.setSettleId(settleId);
    mergeItem(item, request);
    itemMapper.insert(item);
    return item;
  }

  @Override
  public PriceSettleItem updateItem(Long id, PriceSettleItemUpdateRequest request) {
    if (id == null) return null;
    PriceSettleItem existing = itemMapper.selectById(id);
    if (existing == null) return null;
    mergeItem(existing, request);
    itemMapper.updateById(existing);
    return existing;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean deleteItem(Long id) {
    return id != null && itemMapper.deleteById(id) > 0;
  }

  private PriceSettle findExistingSettle(PriceSettleImportRequest req) {
    var query = Wrappers.lambdaQuery(PriceSettle.class);
    if (StringUtils.hasText(req.getBuyer())) {
      query.eq(PriceSettle::getBuyer, req.getBuyer().trim());
    } else {
      query.isNull(PriceSettle::getBuyer);
    }
    if (StringUtils.hasText(req.getSeller())) {
      query.eq(PriceSettle::getSeller, req.getSeller().trim());
    } else {
      query.isNull(PriceSettle::getSeller);
    }
    if (StringUtils.hasText(req.getMonth())) {
      query.eq(PriceSettle::getMonth, req.getMonth().trim());
    } else {
      query.isNull(PriceSettle::getMonth);
    }
    return settleMapper.selectOne(query.last("LIMIT 1"));
  }

  private PriceSettleItem findExistingItem(Long settleId, String materialCode) {
    return itemMapper.selectOne(
        Wrappers.lambdaQuery(PriceSettleItem.class)
            .eq(PriceSettleItem::getSettleId, settleId)
            .eq(PriceSettleItem::getMaterialCode, materialCode)
            .last("LIMIT 1"));
  }

  private void fillItem(PriceSettleItem item, PriceSettleImportRequest.PriceSettleItemImportRow row) {
    item.setMaterialCode(row.getMaterialCode().trim());
    item.setMaterialName(row.getMaterialName());
    item.setModel(row.getModel());
    item.setPlannedPrice(row.getPlannedPrice());
    item.setMarkupRatio(row.getMarkupRatio());
    item.setBaseSettlePrice(row.getBaseSettlePrice());
    item.setLinkedSettlePrice(row.getLinkedSettlePrice());
    item.setRemark(row.getRemark());
  }

  private void mergeSettle(PriceSettle settle, PriceSettleUpdateRequest req) {
    if (req.getBuyer() != null) settle.setBuyer(req.getBuyer());
    if (req.getSeller() != null) settle.setSeller(req.getSeller());
    if (req.getBusinessType() != null) settle.setBusinessType(req.getBusinessType());
    if (req.getProductProperty() != null) settle.setProductProperty(req.getProductProperty());
    if (req.getCopperPrice() != null) settle.setCopperPrice(req.getCopperPrice());
    if (req.getMonth() != null) settle.setMonth(req.getMonth());
    if (req.getApprovalContent() != null) settle.setApprovalContent(req.getApprovalContent());
  }

  private void mergeItem(PriceSettleItem item, PriceSettleItemUpdateRequest req) {
    if (req.getMaterialCode() != null) item.setMaterialCode(req.getMaterialCode());
    if (req.getMaterialName() != null) item.setMaterialName(req.getMaterialName());
    if (req.getModel() != null) item.setModel(req.getModel());
    if (req.getPlannedPrice() != null) item.setPlannedPrice(req.getPlannedPrice());
    if (req.getMarkupRatio() != null) item.setMarkupRatio(req.getMarkupRatio());
    if (req.getBaseSettlePrice() != null) item.setBaseSettlePrice(req.getBaseSettlePrice());
    if (req.getLinkedSettlePrice() != null) item.setLinkedSettlePrice(req.getLinkedSettlePrice());
    if (req.getRemark() != null) item.setRemark(req.getRemark());
  }
}
