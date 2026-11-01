package com.sanhua.marketingcost.service.pricing.inquiry;

import java.text.Normalizer;
import java.util.Locale;

/** Shared inquiry identity normalization for lookup and unadopted-input revisions. */
public final class InquiryNames {
  private InquiryNames() {}

  public static String normalize(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\s\\p{Z}]", "")
            .toUpperCase(Locale.ROOT);
  }
}
