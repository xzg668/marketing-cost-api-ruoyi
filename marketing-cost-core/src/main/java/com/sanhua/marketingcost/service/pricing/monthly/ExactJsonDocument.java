package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/** Lossless JSON object canonicalization with an explicit caller-owned UTF-8 size budget. */
public final class ExactJsonDocument {
  public static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;
  private static final int CACHED_TEXT_LIMIT=64*1024;
  private record Key(String document,int maximumBytes) {}
  // Include the caller's byte limit; a prior wider limit must never authorize a smaller one.
  private static final Cache<Key,String> CANONICAL=Caffeine.newBuilder().maximumWeight(16L*1024*1024)
      .weigher((Key key,String value)->128+2*key.document().length()+2*value.length())
      .expireAfterAccess(Duration.ofMinutes(5)).build();
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER)
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNumberLength(1024 * 1024).maxStringLength(MAX_DOCUMENT_BYTES).build()).build())
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ExactJsonDocument() {}

  public static String canonicalObject(String document, int maximumBytes) {
    if (maximumBytes <= 0 || maximumBytes > MAX_DOCUMENT_BYTES)
      throw new IllegalArgumentException("Invalid JSON UTF-8 byte budget");
    if (document == null || document.isBlank())
      throw new IllegalArgumentException("A nonempty JSON object is required");
    if (document.length() > maximumBytes || document.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
      throw new IllegalArgumentException("JSON document exceeds UTF-8 byte budget: " + maximumBytes);
    Key key=document.length()<=CACHED_TEXT_LIMIT?new Key(document,maximumBytes):null;
    String cached=key==null?null:CANONICAL.getIfPresent(key);
    if(cached!=null)return cached;
    try {
      var node = JSON.readTree(document);
      if (!node.isObject() || node.isEmpty())
        throw new IllegalArgumentException("Document must be a nonempty JSON object");
      var output = new BoundedJson(maximumBytes);
      canonical(node, 0, output);
      String value=output.text.toString();
      if(key!=null && value.length()<=CACHED_TEXT_LIMIT)CANONICAL.put(key,value);
      return value;
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Document must be unambiguous valid JSON", invalid);
    }
  }

  private static void canonical(JsonNode node, int depth, BoundedJson output) throws JsonProcessingException {
    if (node.isContainerNode() && depth >= 64)
      throw new IllegalArgumentException("JSON document exceeds 64 nested containers");
    if (node.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
      output.append("{");
      boolean first = true;
      for (var field : fields.entrySet()) {
        if (!first) output.append(",");
        first = false;
        output.append(JSON.writeValueAsString(field.getKey())); output.append(":");
        canonical(field.getValue(), depth + 1, output);
      }
      output.append("}");
    } else if (node.isArray()) {
      output.append("[");
      boolean first = true;
      for (var value : node) {
        if (!first) output.append(",");
        first = false; canonical(value, depth + 1, output);
      }
      output.append("]");
    } else if (node.isNumber()) {
      output.append(MonthlyPriceCanonical.number(node.decimalValue()));
    } else {
      output.append(node.toString());
    }
  }

  private static final class BoundedJson {
    private final StringBuilder text = new StringBuilder();
    private final int maximumBytes;
    private int bytes;
    private BoundedJson(int maximumBytes) { this.maximumBytes = maximumBytes; }
    private void append(String token) {
      if (token.length() > maximumBytes - bytes)
        throw new IllegalArgumentException("Canonical JSON exceeds UTF-8 byte budget: " + maximumBytes);
      int tokenBytes = token.getBytes(StandardCharsets.UTF_8).length;
      if (tokenBytes > maximumBytes - bytes)
        throw new IllegalArgumentException("Canonical JSON exceeds UTF-8 byte budget: " + maximumBytes);
      text.append(token); bytes += tokenBytes;
    }
  }
}
