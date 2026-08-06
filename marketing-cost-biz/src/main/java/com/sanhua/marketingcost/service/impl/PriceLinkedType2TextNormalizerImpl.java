package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PriceLinkedType2TextNormalizerImpl
    implements PriceLinkedType2TextNormalizer {

  @Override
  public String normalize(String value) {
    if (value == null) {
      return "";
    }
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace("\uFEFF", "")
        .strip()
        .replaceAll("\\s+", " ")
        .toUpperCase(Locale.ROOT);
  }

  @Override
  public String normalizeHeader(String value) {
    return normalize(value).replace(" ", "");
  }
}
