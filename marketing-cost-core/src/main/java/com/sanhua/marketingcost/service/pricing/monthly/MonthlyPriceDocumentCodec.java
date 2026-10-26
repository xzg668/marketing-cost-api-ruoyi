package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Shared lossless JSON text envelope, separate from database JSON numeric interpretation. */
@Component
public final class MonthlyPriceDocumentCodec {
  private record Document(int schemaVersion, String document) {}
  private final ObjectMapper json;

  public MonthlyPriceDocumentCodec(ObjectMapper mapper) {
    json = mapper.copy().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNumberLength(1024*1024).build());
    json.getFactory().enable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER.mappedFeature());
  }

  public String encode(Object value) {
    try { return wrap(json.writeValueAsString(value)); }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot encode monthly price document", failure); }
  }

  public <T> T decode(String encoded, Class<T> type) {
    try { return json.readValue(unwrap(encoded), type); }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid monthly price document", failure); }
  }

  public String wrap(String document) {
    try { return json.writeValueAsString(new Document(1, document)); }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot encode document envelope", failure); }
  }

  public String unwrap(String encoded) {
    try {
      Document value = json.readValue(encoded, Document.class);
      if (value == null || value.schemaVersion()!=1 || value.document()==null) {
        throw new IllegalArgumentException("Unsupported monthly price document envelope");
      }
      return value.document();
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid document envelope", failure); }
  }

  public void validateObject(String document) {
    try {
      var value=json.readTree(document);
      if (value==null || !value.isObject() || value.isEmpty()) throw new IllegalArgumentException("Nonempty snapshot object required");
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid snapshot JSON",failure); }
  }

  public void validateSourceRows(String document) {
    try {
      var rows=json.readTree(document);
      if (rows==null || !rows.isArray() || rows.isEmpty()) throw new IllegalArgumentException("Source row coordinates required");
      var seen=new java.util.HashSet<Integer>();
      for (var row : rows) {
        if (!row.isIntegralNumber() || !row.canConvertToInt() || row.intValue()<3 || !seen.add(row.intValue())) {
          throw new IllegalArgumentException("Invalid source worksheet row coordinate");
        }
      }
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid source row coordinates",failure); }
  }

  public static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JVM must provide SHA-256", impossible); }
  }
}
