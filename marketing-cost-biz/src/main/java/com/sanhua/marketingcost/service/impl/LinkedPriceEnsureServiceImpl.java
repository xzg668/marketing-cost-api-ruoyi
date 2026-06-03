package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.LinkedPriceFactorSource;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LinkedPriceEnsureServiceImpl implements LinkedPriceEnsureService {
  private static final String CALC_STATUS_OK = "OK";
  private static final String CALC_STATUS_FAILED = "FAILED";

  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final OaFormMapper oaFormMapper;
  private final PriceLinkedCalcServiceImpl priceLinkedCalcService;

  public LinkedPriceEnsureServiceImpl(
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      BomCostingRowMapper bomCostingRowMapper,
      OaFormMapper oaFormMapper,
      PriceLinkedCalcServiceImpl priceLinkedCalcService) {
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.oaFormMapper = oaFormMapper;
    this.priceLinkedCalcService = priceLinkedCalcService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public LinkedPriceEnsureResult ensure(LinkedPriceEnsureRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("联动价 ensure 请求不能为空");
    }
    Set<String> itemCodes = request.normalizedItemCodes();
    LinkedPriceEnsureResult result = new LinkedPriceEnsureResult();
    result.setRequestedCount(itemCodes.size());
    if (itemCodes.isEmpty()) {
      return result;
    }

    List<String> errors = request.validate();
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("；", errors));
    }
    if (request.getCalcScene() == LinkedPriceCalcScene.MONTHLY_ADJUST) {
      return ensureMonthlyAdjust(request, itemCodes, result);
    }
    if (request.getCalcScene() != LinkedPriceCalcScene.QUOTE) {
      throw new IllegalArgumentException("不支持的联动价计算场景：" + request.getCalcScene());
    }
    return ensureQuote(request, itemCodes, result);
  }

  private LinkedPriceEnsureResult ensureQuote(
      LinkedPriceEnsureRequest request,
      Set<String> itemCodes,
      LinkedPriceEnsureResult result) {
    String oaNo = request.getOaNo().trim();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    Map<String, PriceLinkedCalcItem> existingMap =
        fetchExistingQuoteResults(oaNo, businessUnitType, pricingMonth, itemCodes);
    Map<String, PriceLinkedItem> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            request.getPriceAsOfTime() == null ? null : request.getPriceAsOfTime().toLocalDate());
    Map<String, BomSnapshot> bomMap = fetchBomSnapshots(oaNo, businessUnitType, itemCodes);
    OaForm oaForm = fetchOaForm(oaNo);

    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem existing = existingMap.get(itemCode);
      if (canSkip(existing, request.isForceRefresh())) {
        result.setSkippedCount(result.getSkippedCount() + 1);
        continue;
      }
      boolean created = existing == null;
      PriceLinkedCalcItem calcItem = created
          ? new PriceLinkedCalcItem()
          : existing;
      populateQuoteContext(calcItem, oaNo, businessUnitType, pricingMonth, itemCode, bomMap);
      PriceLinkedItem linkedItem = linkedItemMap.get(itemCode);
      try {
        priceLinkedCalcService.calculateQuoteItemForEnsure(calcItem, linkedItem, oaForm);
      } catch (RuntimeException ex) {
        calcItem.setPartUnitPrice(null);
        calcItem.setPartAmount(null);
        calcItem.setCalcStatus(CALC_STATUS_FAILED);
        calcItem.setCalcMessage(ex.getMessage());
      }
      persist(calcItem, created);
      if (created) {
        result.setCreatedCount(result.getCreatedCount() + 1);
      } else {
        result.setUpdatedCount(result.getUpdatedCount() + 1);
      }
      if (CALC_STATUS_FAILED.equalsIgnoreCase(calcItem.getCalcStatus())) {
        result.addFailedItem(itemCode, calcItem.getCalcMessage());
      }
    }
    return result;
  }

  private LinkedPriceEnsureResult ensureMonthlyAdjust(
      LinkedPriceEnsureRequest request,
      Set<String> itemCodes,
      LinkedPriceEnsureResult result) {
    Long adjustBatchId = request.getAdjustBatchId();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    Map<String, PriceLinkedCalcItem> existingMap =
        fetchExistingMonthlyAdjustResults(adjustBatchId, businessUnitType, pricingMonth, itemCodes);
    Map<String, PriceLinkedItem> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            request.getPriceAsOfTime() == null ? null : request.getPriceAsOfTime().toLocalDate());

    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem existing = existingMap.get(itemCode);
      if (canSkip(existing, request.isForceRefresh())) {
        result.setSkippedCount(result.getSkippedCount() + 1);
        continue;
      }
      boolean created = existing == null;
      PriceLinkedCalcItem calcItem = created
          ? new PriceLinkedCalcItem()
          : existing;
      populateMonthlyAdjustContext(calcItem, adjustBatchId, businessUnitType, pricingMonth, itemCode);
      PriceLinkedItem linkedItem = linkedItemMap.get(itemCode);
      try {
        priceLinkedCalcService.calculateMonthlyAdjustItemForEnsure(calcItem, linkedItem);
      } catch (RuntimeException ex) {
        calcItem.setPartUnitPrice(null);
        calcItem.setPartAmount(null);
        calcItem.setCalcStatus(CALC_STATUS_FAILED);
        calcItem.setCalcMessage(ex.getMessage());
      }
      persist(calcItem, created);
      if (created) {
        result.setCreatedCount(result.getCreatedCount() + 1);
      } else {
        result.setUpdatedCount(result.getUpdatedCount() + 1);
      }
      if (CALC_STATUS_FAILED.equalsIgnoreCase(calcItem.getCalcStatus())) {
        result.addFailedItem(itemCode, calcItem.getCalcMessage());
      }
    }
    return result;
  }

  private Map<String, PriceLinkedCalcItem> fetchExistingQuoteResults(
      String oaNo, String businessUnitType, String pricingMonth, Set<String> itemCodes) {
    List<PriceLinkedCalcItem> rows = priceLinkedCalcItemMapper.selectList(
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.QUOTE.getCode())
            .eq(PriceLinkedCalcItem::getOaNo, oaNo)
            .eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType)
            .eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth)
            .in(PriceLinkedCalcItem::getItemCode, itemCodes));
    Map<String, PriceLinkedCalcItem> map = new LinkedHashMap<>();
    for (PriceLinkedCalcItem row : rows) {
      if (StringUtils.hasText(row.getItemCode())) {
        map.put(row.getItemCode().trim(), row);
      }
    }
    return map;
  }

  private Map<String, PriceLinkedCalcItem> fetchExistingMonthlyAdjustResults(
      Long adjustBatchId, String businessUnitType, String pricingMonth, Set<String> itemCodes) {
    var query = Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
        .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.MONTHLY_ADJUST.getCode())
        .eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType)
        .eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth)
        .in(PriceLinkedCalcItem::getItemCode, itemCodes);
    if (adjustBatchId == null) {
      query.isNull(PriceLinkedCalcItem::getAdjustBatchId);
    } else {
      query.eq(PriceLinkedCalcItem::getAdjustBatchId, adjustBatchId);
    }
    List<PriceLinkedCalcItem> rows = priceLinkedCalcItemMapper.selectList(query);
    Map<String, PriceLinkedCalcItem> map = new LinkedHashMap<>();
    for (PriceLinkedCalcItem row : rows) {
      if (StringUtils.hasText(row.getItemCode())) {
        map.put(row.getItemCode().trim(), row);
      }
    }
    return map;
  }

  private Map<String, PriceLinkedItem> fetchLinkedItems(
      String businessUnitType, String pricingMonth, Set<String> itemCodes, LocalDate priceDate) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getDeleted, 0)
        .eq(PriceLinkedItem::getBusinessUnitType, businessUnitType)
        .le(PriceLinkedItem::getPricingMonth, pricingMonth)
        .in(PriceLinkedItem::getMaterialCode, itemCodes);
    if (priceDate != null) {
      query.and(q -> q.le(PriceLinkedItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceLinkedItem::getEffectiveFrom));
      query.and(q -> q.ge(PriceLinkedItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceLinkedItem::getEffectiveTo));
    } else {
      query.isNull(PriceLinkedItem::getEffectiveTo);
    }
    List<PriceLinkedItem> rows = priceLinkedItemMapper.selectList(
        query.orderByDesc(PriceLinkedItem::getPricingMonth)
            .orderByDesc(PriceLinkedItem::getQuota)
            .orderByDesc(PriceLinkedItem::getEffectiveFrom)
            .orderByDesc(PriceLinkedItem::getUpdatedAt)
            .orderByDesc(PriceLinkedItem::getId));
    Map<String, PriceLinkedItem> map = new LinkedHashMap<>();
    for (PriceLinkedItem row : rows) {
      if (StringUtils.hasText(row.getMaterialCode())) {
        map.putIfAbsent(row.getMaterialCode().trim(), row);
      }
    }
    return map;
  }

  private Map<String, BomSnapshot> fetchBomSnapshots(
      String oaNo, String businessUnitType, Set<String> itemCodes) {
    List<BomCostingRow> rows = bomCostingRowMapper.selectList(
        Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getOaNo, oaNo)
            .eq(BomCostingRow::getBusinessUnitType, businessUnitType)
            .in(BomCostingRow::getMaterialCode, itemCodes));
    Map<String, BomSnapshot> map = new HashMap<>();
    for (BomCostingRow row : rows) {
      if (!StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      String itemCode = row.getMaterialCode().trim();
      BomSnapshot snapshot = map.computeIfAbsent(itemCode, key -> new BomSnapshot());
      if (row.getQtyPerTop() != null) {
        snapshot.bomQty = snapshot.bomQty == null
            ? row.getQtyPerTop()
            : snapshot.bomQty.add(row.getQtyPerTop());
      }
      if (!StringUtils.hasText(snapshot.shapeAttr) && StringUtils.hasText(row.getShapeAttr())) {
        snapshot.shapeAttr = row.getShapeAttr().trim();
      }
    }
    return map;
  }

  private OaForm fetchOaForm(String oaNo) {
    return oaFormMapper.selectOne(
        Wrappers.lambdaQuery(OaForm.class)
            .eq(OaForm::getOaNo, oaNo)
            .last("LIMIT 1"));
  }

  private boolean canSkip(PriceLinkedCalcItem existing, boolean forceRefresh) {
    return existing != null
        && !forceRefresh
        && CALC_STATUS_OK.equalsIgnoreCase(existing.getCalcStatus())
        && existing.getPartUnitPrice() != null;
  }

  private void populateQuoteContext(
      PriceLinkedCalcItem calcItem,
      String oaNo,
      String businessUnitType,
      String pricingMonth,
      String itemCode,
      Map<String, BomSnapshot> bomMap) {
    BomSnapshot bom = bomMap.get(itemCode);
    calcItem.setOaNo(oaNo);
    calcItem.setBusinessUnitType(businessUnitType);
    calcItem.setPricingMonth(pricingMonth);
    calcItem.setCalcScene(LinkedPriceCalcScene.QUOTE.getCode());
    calcItem.setFactorSource(LinkedPriceCalcScene.QUOTE.getDefaultFactorSource().getCode());
    calcItem.setAdjustBatchId(null);
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(bom == null ? null : bom.shapeAttr);
    calcItem.setBomQty(bom == null ? null : bom.bomQty);
  }

  private void populateMonthlyAdjustContext(
      PriceLinkedCalcItem calcItem,
      Long adjustBatchId,
      String businessUnitType,
      String pricingMonth,
      String itemCode) {
    // 月度调价联动价按“调价批次 + 月份 + 料号”固化，不绑定具体 OA 单。
    calcItem.setOaNo(null);
    calcItem.setBusinessUnitType(businessUnitType);
    calcItem.setPricingMonth(pricingMonth);
    calcItem.setCalcScene(LinkedPriceCalcScene.MONTHLY_ADJUST.getCode());
    calcItem.setFactorSource(monthlyFactorSource(adjustBatchId));
    calcItem.setAdjustBatchId(adjustBatchId);
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(null);
    calcItem.setBomQty(null);
  }

  private void persist(PriceLinkedCalcItem calcItem, boolean created) {
    if (created) {
      priceLinkedCalcItemMapper.insert(calcItem);
    } else {
      priceLinkedCalcItemMapper.updateById(calcItem);
    }
  }

  private String monthlyFactorSource(Long adjustBatchId) {
    return adjustBatchId == null
        ? LinkedPriceFactorSource.MONTHLY_FACTOR.getCode()
        : LinkedPriceFactorSource.ADJUST_BATCH.getCode();
  }

  private static class BomSnapshot {
    private BigDecimal bomQty;
    private String shapeAttr;
  }
}
