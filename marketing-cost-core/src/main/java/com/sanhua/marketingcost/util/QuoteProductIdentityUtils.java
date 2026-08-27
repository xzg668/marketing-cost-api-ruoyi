package com.sanhua.marketingcost.util;

import com.sanhua.marketingcost.entity.OaFormItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 报价产品在尚无正式料号时使用的稳定核算身份。 */
public final class QuoteProductIdentityUtils {

  private static final int MAX_COSTING_CODE_LENGTH = 64;

  private QuoteProductIdentityUtils() {}

  /** 优先使用正式料号；新品依次使用三花型号、客户图号生成内部核算键。 */
  public static String resolveCostingCode(OaFormItem item) {
    if (item == null) {
      return null;
    }
    return resolveCostingCode(
        item.getMaterialNo(), item.getSunlModel(), item.getCustomerDrawing());
  }

  public static String resolveCostingCode(
      String materialNo, String productModel, String drawingNo) {
    String material = trimToNull(materialNo);
    if (material != null) {
      return material;
    }
    String model = trimToNull(productModel);
    if (model != null) {
      return synthetic("MODEL", model);
    }
    String drawing = trimToNull(drawingNo);
    return drawing == null ? null : synthetic("DRAWING", drawing);
  }

  public static boolean hasCostingIdentity(OaFormItem item) {
    return resolveCostingCode(item) != null;
  }

  public static boolean hasFormalMaterialNo(OaFormItem item) {
    return item != null && trimToNull(item.getMaterialNo()) != null;
  }

  private static String synthetic(String type, String value) {
    String normalized = value.toUpperCase(Locale.ROOT);
    String readable = type + ":" + normalized;
    if (readable.length() <= MAX_COSTING_CODE_LENGTH) {
      return readable;
    }
    String prefix = type + "#";
    String digest = sha256(normalized);
    return prefix + digest.substring(0, MAX_COSTING_CODE_LENGTH - prefix.length());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
