package com.sanhua.marketingcost.service.bomalternative;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 基于规范化业务位置的替代组稳定键实现。 */
@Component
public final class BomAlternativeGroupKeyGeneratorImpl
    implements BomAlternativeGroupKeyGenerator {

  private static final String CONTRACT_VERSION = "BOM_ALT_GROUP:v1;";

  @Override
  public String generate(BomAlternativeGroupIdentity identity) {
    if (identity == null) {
      throw new IllegalArgumentException("替代组稳定身份不能为空");
    }
    StringBuilder canonical = new StringBuilder(CONTRACT_VERSION);
    append(canonical, "price_org_code", normalize(identity.priceOrgCode()));
    append(canonical, "top_product_code", normalize(identity.topProductCode()));
    append(
        canonical,
        "parent_path_fingerprint",
        normalize(identity.parentPathFingerprint()));
    append(canonical, "parent_material_no", normalize(identity.parentMaterialNo()));
    append(canonical, "bom_purpose", normalize(identity.bomPurpose()));
    append(canonical, "bom_version", normalize(identity.bomVersion()));
    append(canonical, "effective_from", date(identity.effectiveFrom()));
    append(canonical, "effective_to", date(identity.effectiveTo()));
    append(
        canonical,
        "child_seq",
        identity.childSeq() == null ? "" : identity.childSeq().toString());
    append(canonical, "process_seq", normalize(identity.processSeq()));
    return sha256(canonical.toString());
  }

  @Override
  public String parentPathFingerprint(String parentPath) {
    return sha256(normalizePath(parentPath));
  }

  private static String normalizePath(String value) {
    String normalized = normalize(value).replace('\\', '/');
    if (normalized.isEmpty()) {
      return "";
    }
    normalized = normalized.replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    return normalized;
  }

  private static String normalize(String value) {
    return BomChildType.normalize(value);
  }

  private static String date(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private static void append(StringBuilder target, String name, String value) {
    String normalized = value == null ? "" : value;
    target.append(name)
        .append('=')
        .append(normalized.length())
        .append(':')
        .append(normalized)
        .append(';');
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前JVM不支持SHA-256", ex);
    }
  }
}
