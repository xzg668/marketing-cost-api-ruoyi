package com.sanhua.marketingcost.service.pricing.inquiry;

import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** One quotation's complete, non-overlapping market-price bands; lower inclusive, upper exclusive. */
public record InquiryRangePricing(String factorKey, String marketUnit, List<Band> bands) {
  public record Band(BigDecimal lower, BigDecimal upper, BigDecimal price, int sourceRow) {
    public Band {
      if (lower == null || lower.signum() < 0 || upper != null && upper.compareTo(lower) <= 0)
        throw new IllegalArgumentException("区间下限须非负，上限须大于下限；最后一档上限可以留空");
      InquiryWorkbookParser.amount(price);
    }
  }

  public InquiryRangePricing {
    if (factorKey == null || factorKey.isBlank()) throw new IllegalArgumentException("区间价缺少行情因素");
    FactorPriceUnit.require(marketUnit);
    if (bands == null || bands.isEmpty()) throw new IllegalArgumentException("区间价至少填写一档");
    bands = bands.stream().sorted(Comparator.comparing(Band::lower)).toList();
    for (int index = 1; index < bands.size(); index++) {
      var previous = bands.get(index - 1);
      if (previous.upper() == null || previous.upper().compareTo(bands.get(index).lower()) > 0)
        throw new IllegalArgumentException("同一询价的行情区间重叠，请检查第" + bands.get(index).sourceRow() + "行");
    }
  }

  public BigDecimal price(BigDecimal marketPrice) {
    if (marketPrice == null) throw new IllegalArgumentException("区间询价缺少行情价");
    return bands.stream()
        .filter(band -> marketPrice.compareTo(band.lower()) >= 0
            && (band.upper() == null || marketPrice.compareTo(band.upper()) < 0))
        .findFirst().orElseThrow(() -> new IllegalArgumentException(
            "行情价" + marketPrice.toPlainString() + marketUnit + "未命中询价区间"))
        .price();
  }
}
