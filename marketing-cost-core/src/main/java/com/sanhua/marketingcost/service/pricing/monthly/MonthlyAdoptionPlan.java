package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure adoption input; request entries are lookup conditions, not database unique keys. */
public record MonthlyAdoptionPlan(
    Map<MonthlyPriceRequest, Selection> selections,
    List<Use> uses,
    Set<String> suppressedPaths,
    Set<MonthlyPriceRequest> ordinaryScopeRequests) {

  public MonthlyAdoptionPlan(Map<MonthlyPriceRequest, Selection> selections, List<Use> uses,
      Set<String> suppressedPaths) {
    this(selections, uses, suppressedPaths, Set.of());
  }

  public MonthlyAdoptionPlan {
    selections = Map.copyOf(selections);
    uses = List.copyOf(uses);
    suppressedPaths = Set.copyOf(suppressedPaths);
    // Includes unpriced structure/filter nodes whose adoption as HOUSEHOLD would change this tree.
    // These are read/lock observations only; they must never create placeholder monthly prices.
    ordinaryScopeRequests = Set.copyOf(ordinaryScopeRequests);
    if (uses.isEmpty() && !suppressedPaths.isEmpty()) {
      throw new IllegalArgumentException("Suppressed paths require an effective household use");
    }
  }

  public record Selection(
      Long monthlyPriceId, MonthlyPriceApplicability applicability, PriceFact fact) {
    public Selection {
      Objects.requireNonNull(applicability, "applicability");
      Objects.requireNonNull(fact, "fact");
    }

    public Selection(Long monthlyPriceId, MonthlyPriceCoverage coverage, PriceFact fact) {
      this(monthlyPriceId, MonthlyPriceApplicability.of(coverage), fact);
    }
  }

  public record Use(
      String path, MonthlyPriceRequest request, BigDecimal cumulativeQuantity) {
    public Use {
      if (path == null || path.isBlank()) throw new IllegalArgumentException("Use path is required");
      path = path.trim();
      Objects.requireNonNull(request, "request");
      if (cumulativeQuantity == null || cumulativeQuantity.signum() <= 0) {
        throw new IllegalArgumentException("Effective use quantity must be positive");
      }
      cumulativeQuantity = cumulativeQuantity.stripTrailingZeros();
    }
  }

  public enum PricingType {
    NORMAL,
    HOUSEHOLD_INQUIRY
  }

  public record PriceFact(
      PricingType type,
      BigDecimal price,
      BigDecimal householdRate,
      SourceEvidence evidence,
      Set<MonthlyPriceRequest> dependencies) {

    public PriceFact {
      Objects.requireNonNull(type, "type");
      price = type == PricingType.NORMAL
          ? exactNonnegative(price, 38, 18, "ordinary price")
          : exactNonnegative(price, 24, 12, "household price");
      Objects.requireNonNull(evidence, "evidence");
      dependencies = Set.copyOf(dependencies);
      if (type == PricingType.NORMAL && householdRate != null) {
        throw new IllegalArgumentException("Ordinary facts cannot carry a household rate");
      }
      if (type == PricingType.HOUSEHOLD_INQUIRY) {
        householdRate = exactNonnegative(householdRate, 18, 12, "household rate");
        if (!dependencies.isEmpty()) {
          throw new IllegalArgumentException("Household facts cannot depend on included child prices");
        }
      }
    }

    public static PriceFact normal(
        BigDecimal price, SourceEvidence evidence, Set<MonthlyPriceRequest> dependencies) {
      return new PriceFact(PricingType.NORMAL, price, null, evidence, dependencies);
    }

    public static PriceFact household(
        BigDecimal price, BigDecimal householdRate, SourceEvidence evidence) {
      return new PriceFact(
          PricingType.HOUSEHOLD_INQUIRY, price, householdRate, evidence, Set.of());
    }
  }

  private static BigDecimal exactNonnegative(
      BigDecimal value, int precision, int scale, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be nonnegative and present");
    }
    BigDecimal normalized = value.stripTrailingZeros();
    if (Math.max(0, normalized.scale()) > scale
        || (long) normalized.precision() - normalized.scale() > precision - scale) {
      throw new IllegalArgumentException(field + " exceeds its exact storage precision");
    }
    return normalized;
  }

  public record SourceEvidence(String json) {
    private static final int MAX_UTF8_BYTES = 1024 * 1024;
    private static final int MAX_CONTAINER_DEPTH = 64;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        // Use the exact big-number parser for long integer-valued decimal tokens.
        .enable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER)
        .streamReadConstraints(StreamReadConstraints.builder()
            // Raw tokens may contain redundant zeros; canonical numbers have a separate limit.
            .maxNumberLength(MAX_UTF8_BYTES).build())
        .build())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SourceEvidence {
      if (json == null || json.isBlank()) {
        throw new IllegalArgumentException("A nonempty source evidence object is required");
      }
      if (json.length() > MAX_UTF8_BYTES
          || json.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
        throw new IllegalArgumentException("Source evidence exceeds the 1 MiB UTF-8 limit");
      }
      try {
        JsonNode node = JSON.readTree(json);
        if (!node.isObject() || node.isEmpty()) {
          throw new IllegalArgumentException("Source evidence must be a nonempty JSON object");
        }
        var output = new BoundedJson();
        canonical(node, 0, output);
        json = output.text.toString();
      } catch (JsonProcessingException invalidJson) {
        throw new IllegalArgumentException("Source evidence must be unambiguous valid JSON", invalidJson);
      }
    }

    public static SourceEvidence ofJson(String json) {
      return new SourceEvidence(json);
    }

    public String sha256() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("The JVM must provide SHA-256", impossible);
      }
    }

    private static void canonical(JsonNode node, int depth, BoundedJson output)
        throws JsonProcessingException {
      if (node.isContainerNode() && depth >= MAX_CONTAINER_DEPTH) {
        throw new IllegalArgumentException("Source evidence exceeds 64 nested containers");
      }
      if (node.isObject()) {
        var fields = new TreeMap<String, JsonNode>();
        node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        output.append("{");
        boolean first = true;
        for (var field : fields.entrySet()) {
          if (!first) output.append(",");
          first = false;
          output.append(JSON.writeValueAsString(field.getKey()));
          output.append(":");
          canonical(field.getValue(), depth + 1, output);
        }
        output.append("}");
        return;
      }
      if (node.isArray()) {
        output.append("[");
        boolean first = true;
        for (JsonNode value : node) {
          if (!first) output.append(",");
          first = false;
          canonical(value, depth + 1, output);
        }
        output.append("]");
        return;
      }
      if (node.isNumber()) {
        output.append(MonthlyPriceCanonical.number(node.decimalValue()));
        return;
      }
      output.append(node.toString());
    }

    private static final class BoundedJson {
      private final StringBuilder text = new StringBuilder();
      private int bytes;

      private void append(String token) {
        if (token.length() > MAX_UTF8_BYTES - bytes) {
          throw new IllegalArgumentException("Canonical source evidence exceeds 1 MiB");
        }
        int tokenBytes = token.getBytes(StandardCharsets.UTF_8).length;
        if (tokenBytes > MAX_UTF8_BYTES - bytes) {
          throw new IllegalArgumentException("Canonical source evidence exceeds 1 MiB");
        }
        text.append(token);
        bytes += tokenBytes;
      }
    }
  }
}
