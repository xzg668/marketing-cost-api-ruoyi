package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunPartItemServiceImpl implements CostRunPartItemService {
  private static final String FIXED_PRICE_TYPE = "固定价";
  private static final String LINKED_PRICE_TYPE = "联动价";

  private final CostRunPartItemMapper costRunPartItemMapper;
  private final PriceFixedItemMapper priceFixedItemMapper;
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;

  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      PriceFixedItemMapper priceFixedItemMapper,
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.priceFixedItemMapper = priceFixedItemMapper;
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItemDto> items = costRunPartItemMapper.selectBaseByOaNo(oaNoValue);
    if (items.isEmpty()) {
      return items;
    }

    Set<String> fixedCodes = new LinkedHashSet<>();
    Set<String> linkedCodes = new LinkedHashSet<>();
    for (CostRunPartItemDto item : items) {
      String code = item.getPartCode();
      if (!StringUtils.hasText(code)) {
        continue;
      }
      String priceType = StringUtils.hasText(item.getPriceType())
          ? item.getPriceType().trim()
          : "";
      if (FIXED_PRICE_TYPE.equals(priceType)) {
        fixedCodes.add(code);
      } else if (LINKED_PRICE_TYPE.equals(priceType)) {
        linkedCodes.add(code);
      }
    }

    Map<String, BigDecimal> fixedPriceMap = new HashMap<>();
    if (!fixedCodes.isEmpty()) {
      List<PriceFixedItem> fixedItems =
          priceFixedItemMapper.selectList(
              Wrappers.lambdaQuery(PriceFixedItem.class)
                  .in(PriceFixedItem::getMaterialCode, fixedCodes)
                  .orderByDesc(PriceFixedItem::getEffectiveFrom)
                  .orderByDesc(PriceFixedItem::getId));
      for (PriceFixedItem item : fixedItems) {
        String code = item.getMaterialCode();
        if (!fixedPriceMap.containsKey(code)) {
          fixedPriceMap.put(code, item.getFixedPrice());
        }
      }
    }

    Map<String, BigDecimal> linkedPriceMap = new HashMap<>();
    if (!linkedCodes.isEmpty()) {
      List<PriceLinkedCalcItem> linkedItems =
          priceLinkedCalcItemMapper.selectList(
              Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
                  .eq(PriceLinkedCalcItem::getOaNo, oaNoValue)
                  .in(PriceLinkedCalcItem::getItemCode, linkedCodes)
                  .orderByDesc(PriceLinkedCalcItem::getId));
      for (PriceLinkedCalcItem item : linkedItems) {
        String code = item.getItemCode();
        if (!linkedPriceMap.containsKey(code)) {
          linkedPriceMap.put(code, item.getPartUnitPrice());
        }
      }
    }

    for (CostRunPartItemDto item : items) {
      item.setPriceSource("");
      item.setRemark("");
      String code = item.getPartCode();
      String priceType = StringUtils.hasText(item.getPriceType())
          ? item.getPriceType().trim()
          : "";
      BigDecimal unitPrice = null;
      if (StringUtils.hasText(code)) {
        if (FIXED_PRICE_TYPE.equals(priceType)) {
          unitPrice = fixedPriceMap.get(code);
        } else if (LINKED_PRICE_TYPE.equals(priceType)) {
          unitPrice = linkedPriceMap.get(code);
        }
      }
      item.setUnitPrice(unitPrice);
      if (unitPrice != null && item.getPartQty() != null) {
        item.setAmount(unitPrice.multiply(item.getPartQty()));
      }
    }
    saveCostRunItems(oaNoValue, items);
    return items;
  }

  @Override
  public List<CostRunPartItemDto> listStoredByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItem> stored =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getOaNo, oaNoValue));
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunPartItemDto> items = new java.util.ArrayList<>();
    for (CostRunPartItem item : stored) {
      CostRunPartItemDto dto = new CostRunPartItemDto();
      dto.setOaNo(item.getOaNo());
      dto.setProductCode(item.getProductCode());
      dto.setPartCode(item.getPartCode());
      dto.setPartName(item.getPartName());
      dto.setPartDrawingNo(item.getPartDrawingNo());
      dto.setPartQty(item.getQty());
      dto.setMaterial(item.getMaterial());
      dto.setShapeAttr(item.getShapeAttr());
      dto.setPriceSource(item.getPriceSource());
      dto.setUnitPrice(item.getUnitPrice());
      dto.setAmount(item.getAmount());
      dto.setRemark(item.getRemark());
      items.add(dto);
    }
    return items;
  }

  private void saveCostRunItems(String oaNo, List<CostRunPartItemDto> items) {
    if (!StringUtils.hasText(oaNo) || items == null || items.isEmpty()) {
      return;
    }
    costRunPartItemMapper.delete(
        Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getOaNo, oaNo));
    List<CostRunPartItem> entities = new ArrayList<>(items.size());
    for (CostRunPartItemDto item : items) {
      CostRunPartItem entity = new CostRunPartItem();
      entity.setOaNo(oaNo);
      entity.setProductCode(item.getProductCode());
      entity.setPartCode(item.getPartCode());
      entity.setPartName(item.getPartName());
      entity.setPartDrawingNo(item.getPartDrawingNo());
      entity.setQty(item.getPartQty());
      entity.setMaterial(item.getMaterial());
      entity.setShapeAttr(item.getShapeAttr());
      entity.setPriceSource(item.getPriceSource());
      entity.setUnitPrice(item.getUnitPrice());
      entity.setAmount(item.getAmount());
      entity.setRemark(item.getRemark());
      entities.add(entity);
    }
    batchInsert(entities);
  }

  private void batchInsert(List<CostRunPartItem> entities) {
    if (entities.isEmpty()) {
      return;
    }
    for (CostRunPartItem entity : entities) {
      costRunPartItemMapper.insert(entity);
    }
  }
}
