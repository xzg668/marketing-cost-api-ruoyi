package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class QuoteCostingInputFingerprintServiceImpl
    implements QuoteCostingInputFingerprintService {

  private static final String FINGERPRINT_VERSION = "QUOTE_COSTING_INPUT_V1";

  @Override
  public String calculate(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("核算输入不能为空");
    }
    List<String> alternatives = normalizedSorted(input.alternativeSelections());
    List<String> configurations = normalizedSorted(input.configurationVersions());
    List<String> prices = new ArrayList<>();
    for (PriceReference price : input.priceReferences()) {
      if (price != null) {
        prices.add(canonical(
            price.priceTypeRecordId(),
            price.priceType(),
            price.priceSourceRecordId(),
            price.supplierCode(),
            price.supplyRatioRecordId()));
      }
    }
    prices.sort(Comparator.naturalOrder());

    return digest(List.of(
        canonical(
            input.oaFormItemId(),
            input.productCode(),
            input.periodMonth(),
            input.packageMethod(),
            input.packageComponentCode(),
            input.packageQuantity(),
            input.productAttribute(),
            input.businessType(),
            input.bomSourceFingerprint(),
            input.bomRuleFingerprint()),
        canonicalList("ALTERNATIVE", alternatives),
        canonicalList("PRICE", prices),
        canonicalList("CONFIG", configurations)));
  }

  private List<String> normalizedSorted(List<String> values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      result.add(normalize(value));
    }
    result.sort(Comparator.naturalOrder());
    return result;
  }

  private String canonicalList(String type, List<String> values) {
    StringBuilder result = new StringBuilder(type).append('|');
    for (String value : values) {
      result.append(value.length()).append(':').append(value).append('|');
    }
    return result.toString();
  }

  private String canonical(Object... values) {
    StringBuilder result = new StringBuilder();
    for (Object value : values) {
      String text = normalize(value);
      result.append(text.length()).append(':').append(text).append('|');
    }
    return result.toString();
  }

  private String normalize(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private String digest(List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8));
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }
}
