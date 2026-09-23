package com.sanhua.marketingcost.bom;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/** U9 单层 BOM 的业务身份；导入表的自增 ID 和批次号都不属于身份。 */
public record U9BomLineKey(
    String priceOrgCode,
    String parentMaterialNo,
    String childMaterialNo,
    String bomPurpose,
    Integer childSeq,
    String bomVersion,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  public U9BomLineKey {
    priceOrgCode = required(priceOrgCode, "报价组织");
    parentMaterialNo = required(parentMaterialNo, "母件料号");
    childMaterialNo = required(childMaterialNo, "子件料号");
    bomPurpose = required(bomPurpose, "BOM 用途");
    childSeq = Objects.requireNonNull(childSeq, "子件项次不能为空");
    bomVersion = required(bomVersion, "BOM 版本");
    effectiveFrom = Objects.requireNonNull(effectiveFrom, "生效日期不能为空");
    effectiveTo = Objects.requireNonNull(effectiveTo, "失效日期不能为空");
  }

  public static U9BomLineKey from(BomU9Source row) {
    return new U9BomLineKey(row.getPriceOrgCode(), row.getParentMaterialNo(),
        row.getChildMaterialNo(), row.getBomPurpose(), row.getChildSeq(),
        row.getBomVersion(), row.getEffectiveFrom(), row.getEffectiveTo());
  }

  public static U9BomLineKey from(BomRawHierarchy row) {
    return new U9BomLineKey(row.getPriceOrgCode(), row.getParentCode(),
        row.getMaterialCode(), row.getBomPurpose(), row.getSortSeq(),
        row.getBomVersion(), row.getEffectiveFrom(), row.getEffectiveTo());
  }

  public String token() {
    return hash("U9_BOM_LINE_V1", priceOrgCode, parentMaterialNo, childMaterialNo,
        bomPurpose, childSeq.toString(), bomVersion, effectiveFrom.toString(),
        effectiveTo.toString());
  }

  /** 同一业务行可能出现在两个不同的上层分支，节点身份还需包含所在分支。 */
  public String occurrenceToken(String parentNodeKey) {
    return hash("U9_BOM_OCCURRENCE_V1", parentNodeKey, token());
  }

  /** 完整层级行用组织、顶层和稳定路径标识，不使用重导入后变化的行 ID。 */
  public static String hierarchyToken(
      String priceOrgCode, String topProductCode, String bomPurpose, String path) {
    return hash("U9_HIERARCHY_PATH_V1", priceOrgCode, topProductCode, bomPurpose, path);
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim();
  }

  private static String hash(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        if (part == null) {
          updateLength(digest, -1);
        } else {
          byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
          updateLength(digest, bytes.length);
          digest.update(bytes);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }

  private static void updateLength(MessageDigest digest, int length) {
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
  }
}
