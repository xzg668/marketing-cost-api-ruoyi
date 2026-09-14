package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class TechnicalDataSourceSnapshotFactory {
  private final ObjectMapper canonicalMapper;

  public TechnicalDataSourceSnapshotFactory(ObjectMapper objectMapper) {
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public Snapshot create(TechnicalDataTaskPublishRequest.Product product) {
    Map<String, Object> values = new TreeMap<>();
    values.put("oaFormItemId", product.oaFormItemId());
    values.put("levelNo", product.levelNo());
    values.put("materialNo", product.materialNo());
    values.put("productName", product.productName());
    values.put("sourceModel", product.sourceModel());
    values.put("sourceSpec", product.sourceSpec());
    values.put("sourceProductProperty", product.sourceProductProperty());
    values.put("newProduct", Boolean.TRUE.equals(product.newProduct()));
    values.put("nonStandardPackage", Boolean.TRUE.equals(product.nonStandardPackage()));
    values.put("validPackageSource", Boolean.TRUE.equals(product.validPackageSource()));
    values.put("validCmsAuxiliarySource", Boolean.TRUE.equals(product.validCmsAuxiliarySource()));
    values.put("validCmsSalarySource", Boolean.TRUE.equals(product.validCmsSalarySource()));
    values.put("auxiliaryRequested", Boolean.TRUE.equals(product.auxiliaryRequested()));
    values.put("salaryRequested", Boolean.TRUE.equals(product.salaryRequested()));
    values.put("sourceFields", product.sourceFields() == null
        ? Map.of() : new TreeMap<>(product.sourceFields()));
    try {
      String json = canonicalMapper.writeValueAsString(values);
      return new Snapshot(json, sha256(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("OA产品源快照无法序列化", exception);
    }
  }

  public SourceProfile readProfile(String snapshotJson) {
    if (snapshotJson == null || snapshotJson.isBlank()) {
      return new SourceProfile(null, null, false);
    }
    try {
      JsonNode root = canonicalMapper.readTree(snapshotJson);
      return new SourceProfile(
          text(root.get("sourceModel")),
          text(root.get("sourceProductProperty")),
          root.path("newProduct").asBoolean(false));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("已保存的OA产品源快照无法解析", exception);
    }
  }

  private String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("运行环境不支持SHA-256", exception);
    }
  }

  public record Snapshot(String json, String fingerprint) {}

  public record SourceProfile(String productModel, String productProperty, boolean newProduct) {}
}
