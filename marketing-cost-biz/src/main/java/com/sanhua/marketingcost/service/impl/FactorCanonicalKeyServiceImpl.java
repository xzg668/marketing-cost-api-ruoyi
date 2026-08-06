package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FactorCanonicalKeyServiceImpl implements FactorCanonicalKeyService {

  private final PriceLinkedType2TextNormalizer textNormalizer;

  public FactorCanonicalKeyServiceImpl(
      PriceLinkedType2TextNormalizer textNormalizer) {
    this.textNormalizer = textNormalizer;
  }

  @Override
  public String build(String priceSource, String shortName) {
    String sourceKey = priceSourceKey(priceSource);
    String shortKey = compact(textNormalizer.normalize(shortName));
    if (!StringUtils.hasText(sourceKey) || !StringUtils.hasText(shortKey)) {
      return "";
    }
    return sourceKey + "|" + shortKey;
  }

  @Override
  public String normalizeExistingKey(String canonicalFactorKey) {
    String normalized = compact(textNormalizer.normalize(canonicalFactorKey));
    int separator = normalized.indexOf('|');
    if (separator <= 0 || separator == normalized.length() - 1) {
      return normalized;
    }
    String source = priceSourceKey(normalized.substring(0, separator));
    String shortName = normalized.substring(separator + 1);
    return source + "|" + shortName;
  }

  private String priceSourceKey(String priceSource) {
    String normalized = compact(textNormalizer.normalize(priceSource));
    return switch (normalized) {
      case "平均价", "月平均价", "月均价", "AVERAGE", "AVG" -> "AVG";
      case "现货价", "SPOT" -> "SPOT";
      case "采购价", "PURCHASE" -> "PURCHASE";
      case "出厂价", "FACTORY" -> "FACTORY";
      case "招标价", "TENDER" -> "TENDER";
      default -> normalized;
    };
  }

  private String compact(String value) {
    return value == null ? "" : value.replace(" ", "");
  }
}
