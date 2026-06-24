package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunPreparedPartItemProvider;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Uses the step-4 quote price preparation snapshot as the part-price input of quote cost runs.
 */
@Service
public class QuotePricePreparePartItemProviderImpl implements CostRunPreparedPartItemProvider {

  private static final String STATUS_READY = "READY";

  private final PricePrepareItemMapper pricePrepareItemMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final MaterialMasterMapper materialMasterMapper;

  public QuotePricePreparePartItemProviderImpl(
      PricePrepareItemMapper pricePrepareItemMapper,
      BomCostingRowMapper bomCostingRowMapper,
      MaterialMasterMapper materialMasterMapper) {
    this.pricePrepareItemMapper = pricePrepareItemMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.materialMasterMapper = materialMasterMapper;
  }

  @Override
  public boolean supports(CostRunContext context) {
    return context != null
        && CostRunContext.SCENE_QUOTE.equals(context.getScene())
        && StringUtils.hasText(context.getPricePrepareNo());
  }

  @Override
  public List<CostRunPartItemDto> listPreparedPartItems(CostRunContext context) {
    if (!supports(context)) {
      return Collections.emptyList();
    }
    String prepareNo = context.getPricePrepareNo().trim();
    String oaNo = trimToNull(context.getOaNo());
    String productCode = trimToNull(context.getProductCode());
    List<PricePrepareItem> items =
        pricePrepareItemMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareItem.class)
                .eq(PricePrepareItem::getPrepareNo, prepareNo)
                .eq(StringUtils.hasText(oaNo), PricePrepareItem::getOaNo, oaNo)
                .eq(context.getOaFormItemId() != null,
                    PricePrepareItem::getOaFormItemId, context.getOaFormItemId())
                .eq(StringUtils.hasText(productCode), PricePrepareItem::getTopProductCode, productCode)
                .eq(StringUtils.hasText(context.getPricingMonth()),
                    PricePrepareItem::getPeriodMonth, context.getPricingMonth().trim())
                .orderByAsc(PricePrepareItem::getBomRowId)
                .orderByAsc(PricePrepareItem::getId));
    if (items == null || items.isEmpty()) {
      throw new IllegalStateException("价格源快照 " + prepareNo + " 缺少部品明细，不能发起报价成本核算");
    }

    Map<Long, BomCostingRow> bomRows = loadBomRows(items);
    Map<String, MaterialMaster> masters = loadMasters(items, bomRows);
    return items.stream().map(item -> toPartDto(context, item, bomRows, masters)).toList();
  }

  private Map<Long, BomCostingRow> loadBomRows(List<PricePrepareItem> items) {
    Set<Long> ids = new LinkedHashSet<>();
    for (PricePrepareItem item : items) {
      if (item != null && item.getBomRowId() != null) {
        ids.add(item.getBomRowId());
      }
    }
    if (ids.isEmpty()) {
      return Collections.emptyMap();
    }
    List<BomCostingRow> rows =
        bomCostingRowMapper.selectList(
            Wrappers.lambdaQuery(BomCostingRow.class).in(BomCostingRow::getId, ids));
    Map<Long, BomCostingRow> result = new LinkedHashMap<>();
    if (rows != null) {
      for (BomCostingRow row : rows) {
        if (row != null && row.getId() != null) {
          result.putIfAbsent(row.getId(), row);
        }
      }
    }
    return result;
  }

  private Map<String, MaterialMaster> loadMasters(
      List<PricePrepareItem> items, Map<Long, BomCostingRow> bomRows) {
    Set<String> codes = new LinkedHashSet<>();
    for (PricePrepareItem item : items) {
      String code = item == null ? null : trimToNull(item.getMaterialCode());
      if (code != null) {
        codes.add(code);
      }
    }
    for (BomCostingRow row : bomRows.values()) {
      String code = row == null ? null : trimToNull(row.getMaterialCode());
      if (code != null) {
        codes.add(code);
      }
    }
    if (codes.isEmpty()) {
      return Collections.emptyMap();
    }
    List<MaterialMaster> rows =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class).in(MaterialMaster::getMaterialCode, codes));
    Map<String, MaterialMaster> result = new LinkedHashMap<>();
    if (rows != null) {
      for (MaterialMaster row : rows) {
        String code = row == null ? null : trimToNull(row.getMaterialCode());
        if (code != null) {
          result.putIfAbsent(code, row);
        }
      }
    }
    return result;
  }

  private CostRunPartItemDto toPartDto(
      CostRunContext context,
      PricePrepareItem item,
      Map<Long, BomCostingRow> bomRows,
      Map<String, MaterialMaster> masters) {
    BomCostingRow bomRow = item.getBomRowId() == null ? null : bomRows.get(item.getBomRowId());
    String materialCode = firstText(
        item.getMaterialCode(),
        bomRow == null ? null : bomRow.getMaterialCode());
    MaterialMaster master = materialCode == null ? null : masters.get(materialCode);

    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPricePrepareItemId(item.getId());
    dto.setBomRowId(item.getBomRowId());
    dto.setOaNo(firstText(item.getOaNo(), context.getOaNo()));
    dto.setProductCode(firstText(item.getTopProductCode(), context.getProductCode()));
    dto.setPartCode(materialCode);
    dto.setPartName(firstText(
        item.getMaterialName(),
        bomRow == null ? null : bomRow.getMaterialName(),
        master == null ? null : master.getMaterialName()));
    dto.setPartDrawingNo(master == null ? null : trimToNull(master.getDrawingNo()));
    dto.setPartQty(firstAmount(item.getQuantity(), bomRow == null ? null : bomRow.getQtyPerTop()));
    dto.setShapeAttr(firstText(
        bomRow == null ? null : bomRow.getShapeAttr(),
        master == null ? null : master.getShapeAttr()));
    dto.setMaterial(firstText(
        master == null ? null : master.getMaterial(),
        bomRow == null ? null : bomRow.getMaterialSpec(),
        bomRow == null ? null : bomRow.getMaterialAttribute()));
    dto.setPriceSource(trimToNull(item.getPriceSource()));
    dto.setUnitPrice(item.getUnitPrice());
    dto.setAmount(item.getAmount());
    dto.setRemark(remark(item));
    return dto;
  }

  private String remark(PricePrepareItem item) {
    String message = trimToNull(item.getMessage());
    String status = trimToNull(item.getStatus());
    if (STATUS_READY.equals(status)) {
      return message;
    }
    if (message != null) {
      return message;
    }
    return status == null ? null : "价格准备未完成：" + status;
  }

  private BigDecimal firstAmount(BigDecimal first, BigDecimal second) {
    return first == null ? second : first;
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String text = trimToNull(value);
      if (text != null) {
        return text;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
