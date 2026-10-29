package com.sanhua.marketingcost.service.reprice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Identities use saved business coordinates, never report labels, amounts or row numbers. */
public final class MonthlyRepriceContract {
  private MonthlyRepriceContract() {}

  public enum State {
    QUEUED, RUNNING, CHECKING, WAIT_CONFIRM, FAILED, CONFIRMED;

    public boolean blocksQuotation() { return this != CONFIRMED; }

    public void requireTransition(State next) {
      boolean allowed = switch (this) {
        case QUEUED -> next == RUNNING || next == FAILED;
        case RUNNING -> next == CHECKING || next == FAILED;
        case CHECKING -> next == WAIT_CONFIRM || next == FAILED;
        case WAIT_CONFIRM -> next == CONFIRMED || next == FAILED;
        case FAILED -> next == QUEUED || next == CHECKING;
        case CONFIRMED -> false;
      };
      if (!allowed) throw new IllegalStateException("非法调价状态转换：" + this + " -> " + next);
    }
  }

  public record ObjectIdentity(String businessUnitType, String oaNo, long oaFormItemId) {
    public ObjectIdentity {
      unit(businessUnitType);
      text(oaNo, "OA单号");
      if (oaFormItemId <= 0) throw new IllegalArgumentException("实际产品行ID必填");
    }
    public String key() { return hash(List.of(businessUnitType, oaNo, Long.toString(oaFormItemId))); }
  }

  public record Source(ObjectIdentity object, long sourceCostVersionId) {
    public Source {
      Objects.requireNonNull(object);
      if (sourceCostVersionId <= 0) throw new IllegalArgumentException("最近成功报价版本必填");
    }
  }

  /** Path is the saved pricing path; parent path remains separate even for repeated material codes. */
  public record LineIdentity(ObjectIdentity object, String pricingPath, String parentPath,
      String materialCode) {
    public LineIdentity {
      Objects.requireNonNull(object);
      text(pricingPath, "保存的BOM计价路径");
      text(materialCode, "实际计价物料");
      if (parentPath != null) text(parentPath, "保存的母件路径");
    }
    public String key() { return hash(List.of(object.key(), pricingPath, parentPath == null ? "" : parentPath, materialCode)); }
    public String versionKey(YearMonth month, long versionId) {
      Objects.requireNonNull(month);
      if (versionId <= 0) throw new IllegalArgumentException("保存版本ID必填");
      return hash(List.of(key(), month.toString(), Long.toString(versionId)));
    }
  }

  public static void unit(String unit) {
    if (!List.of("COMMERCIAL", "HOUSEHOLD").contains(unit == null ? "" : unit))
      throw new IllegalArgumentException("业务单元无效");
  }

  private static void text(String value, String label) {
    if (value == null || value.isBlank() || !value.equals(value.trim()))
      throw new IllegalArgumentException(label + "必填且不可含首尾空白");
  }

  private static String hash(List<String> parts) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
