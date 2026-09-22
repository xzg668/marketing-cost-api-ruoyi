package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

  public Snapshot create(TechnicalDataProductSource product) {
    Map<String, Object> values = new TreeMap<>();
    values.put("oaFormItemId", product.oaFormItemId());
    values.put("levelNo", product.levelNo());
    values.put("materialNo", product.materialNo());
    values.put("productName", product.productName());
    values.put("sourceModel", product.sourceModel());
    values.put("sourceSpec", product.sourceSpec());
    values.put("sourceProductProperty", product.sourceProductProperty());
    values.put("newProduct", product.newProduct());
    values.put("oaFormId", product.oaFormId());
    values.put("oaNo", product.oaNo());
    values.put("externalLineId", product.externalLineId());
    values.put("annualVolume", product.annualVolume());
    values.put("annualVolumeUnit", product.annualVolumeUnit());
    values.put("packageMethod", product.packageMethod());
    values.put("businessUnitType", product.businessUnitType());
    values.put("applicableOrgCode", product.applicableOrgCode());
    values.put("materialOrganizationCode", product.materialOrganizationCode());
    try {
      String json = canonicalMapper.writeValueAsString(values);
      return new Snapshot(json, sha256(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("OA产品源快照无法序列化", exception);
    }
  }

  public SourceProfile readProfile(String snapshotJson) {
    if (snapshotJson == null || snapshotJson.isBlank()) {
      return new SourceProfile(null, null, null, null, null);
    }
    try {
      JsonNode root = canonicalMapper.readTree(snapshotJson);
      return new SourceProfile(
          text(root.get("sourceModel")),
          text(root.get("sourceProductProperty")),
          root.hasNonNull("newProduct") ? root.path("newProduct").booleanValue() : null,
          root.hasNonNull("annualVolume") ? new java.math.BigDecimal(root.path("annualVolume").asText()) : null,
          text(root.get("annualVolumeUnit")));
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

  public record SourceProfile(String productModel, String productProperty, Boolean newProduct,
      java.math.BigDecimal annualVolume, String annualVolumeUnit) {}
}
