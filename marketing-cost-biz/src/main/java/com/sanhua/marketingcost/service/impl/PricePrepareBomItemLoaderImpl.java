package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Loads only settlement rows generated for the selected quotation product and month. */
@Service
public class PricePrepareBomItemLoaderImpl implements PricePrepareBomItemLoader {
  private final BomCostingRowMapper bomCostingRowMapper;

  public PricePrepareBomItemLoaderImpl(BomCostingRowMapper bomCostingRowMapper) {
    this.bomCostingRowMapper = bomCostingRowMapper;
  }

  @Override
  public List<BomCostingRow> loadByQuoteItem(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    if (!StringUtils.hasText(oaNo) || oaFormItemId == null || oaFormItemId <= 0
        || !StringUtils.hasText(topProductCode) || !StringUtils.hasText(periodMonth)) {
      return List.of();
    }
    var rows = bomCostingRowMapper.selectQuoteCostingSnapshot(
        oaNo.trim(), oaFormItemId, topProductCode.trim(), periodMonth.trim());
    // The quotation builder already resolves packaging against its immutable effective tree.
    // Global raw parents may belong to another build and must not extend this quote's scope.
    return rows == null ? List.of() : List.copyOf(rows);
  }
}
