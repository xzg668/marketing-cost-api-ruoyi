package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Shared lossless JSON text envelope, separate from database JSON numeric interpretation. */
@Component
public final class MonthlyPriceDocumentCodec {
  private record Document(int schemaVersion, String document) {}
  private record DecodedKey(String encoded, Class<?> type) {}
  private record DecodedValue(Object value, int weight) {}
  private static final int MAX_EXPANDED_BYTES = 64 * 1024 * 1024;
  private static final int CACHED_TEXT_LIMIT = 64 * 1024;
  // Only exact immutable text is reused. Database facts/decisions and mutable JSON objects are not cached here.
  private final Cache<String,String> envelopes = textCache();
  private final Cache<String,String> documents = textCache();
  // These records defensively copy every collection. Mutable JSON trees/DTOs are never shared.
  // The exact document is the key: a changed snapshot must be parsed and validated again.
  private final Cache<DecodedKey, DecodedValue> decoded = Caffeine.newBuilder()
          .maximumWeight(64L * 1024 * 1024)
          .weigher((DecodedKey key, DecodedValue value) -> value.weight())
          .expireAfterAccess(Duration.ofMinutes(5)).build();
  private final ObjectMapper json;

  public MonthlyPriceDocumentCodec(ObjectMapper mapper) {
    json = mapper.copy().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    // An envelope holds a whole bounded document as one string, including base64 expansion.
    json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxNumberLength(1024*1024).maxStringLength(2 * MAX_EXPANDED_BYTES).build());
    json.getFactory().enable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER.mappedFeature());
  }

  public String encode(Object value) {
    try { return wrap(json.writeValueAsString(value)); }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot encode monthly price document", failure); }
  }

  public <T> T decode(String encoded, Class<T> type) {
    if (type == MonthlyPreparationPrice.class || type == MonthlyPreparationInputs.class
        || type == MonthlyPriceApplicability.class) {
      return type.cast(decoded.get(new DecodedKey(encoded, type), key -> {
        String document = unwrap(key.encoded());
        return new DecodedValue(read(document,type), (int)Math.min(Integer.MAX_VALUE,
            128L + 2L * key.encoded().length() + 6L * document.length()));
      }).value());
    }
    return decodeUncached(encoded, type);
  }

  private <T> T decodeUncached(String encoded, Class<T> type) {
    return read(unwrap(encoded),type);
  }

  private <T> T read(String document, Class<T> type) {
    try { return json.readValue(document, type); }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid monthly price document", failure); }
  }

  public String wrap(String document) {
    boolean reusable=document!=null && document.length()<=CACHED_TEXT_LIMIT;
    String cached=reusable?envelopes.getIfPresent(document):null;
    if(cached!=null)return cached;
    try {
      String encoded=json.writeValueAsString(new Document(1, document));
      if(reusable && encoded.length()<=CACHED_TEXT_LIMIT)envelopes.put(document,encoded);
      return encoded;
    }
    catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot encode document envelope", failure); }
  }

  public String unwrap(String encoded) {
    boolean reusable=encoded!=null && encoded.length()<=CACHED_TEXT_LIMIT;
    String cached=reusable?documents.getIfPresent(encoded):null;
    if(cached!=null)return cached;
    try {
      Document value = json.readValue(encoded, Document.class);
      if (value == null || value.document()==null || (value.schemaVersion()!=1 && value.schemaVersion()!=2)) {
        throw new IllegalArgumentException("Unsupported monthly price document envelope");
      }
      String document=value.schemaVersion()==1 ? value.document() : expand(value.document());
      if(reusable && document.length()<=CACHED_TEXT_LIMIT)documents.put(encoded,document);
      return document;
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid document envelope", failure); }
  }

  private static Cache<String,String> textCache() {
    return Caffeine.newBuilder().maximumWeight(8L*1024*1024)
        .weigher((String key,String value)->128+2*key.length()+2*value.length())
        .expireAfterAccess(Duration.ofMinutes(5)).build();
  }

  /** Explicit opt-in for lossless storage; callers retain outer metadata needed by SQL readers. */
  public String compact(String encoded) {
    String document = unwrap(encoded);
    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 1024) return encoded;
    if (bytes.length > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Monthly document exceeds storage limit");
    try {
      var output = new java.io.ByteArrayOutputStream();
      try (var gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(bytes); }
      String packed = json.writeValueAsString(new Document(2,java.util.Base64.getEncoder().encodeToString(output.toByteArray())));
      return packed.length() < encoded.length() ? packed : encoded;
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot compact monthly document",failure); }
  }

  private String expand(String encoded) {
    try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)))) {
      var output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192]; int total=0, count;
      while ((count=gzip.read(buffer))!=-1) {
        total+=count;
        if (total>MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded monthly document exceeds storage limit");
        output.write(buffer,0,count);
      }
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid compact monthly document",failure); }
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
