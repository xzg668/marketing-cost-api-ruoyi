package com.sanhua.marketingcost.service.costing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Lossless numeric equality; 1.0 and 1.00 agree, while null, zero and row order remain distinct. */
final class CalculationInputEquality {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private CalculationInputEquality() {}

  static boolean same(Object left, Object right) {
    if (left.equals(right)) return true;
    JsonNode a = JSON.valueToTree(left), b = JSON.valueToTree(right);
    return a.equals(b);
  }
}
