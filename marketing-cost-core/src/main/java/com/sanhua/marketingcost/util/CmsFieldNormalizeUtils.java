package com.sanhua.marketingcost.util;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class CmsFieldNormalizeUtils {
  private static final Pattern ALL_BLANK_CHARS = Pattern.compile("[\\s\\u3000]+");
  private static final Set<String> INVALID_SEQUENCE_STATUSES =
      Set.of("作废", "已作废", "驳回", "已驳回", "取消", "已取消", "已撤回");

  private CmsFieldNormalizeUtils() {}

  /** CMS 料号匹配前先去掉所有空白字符，避免 Excel 中隐藏空格导致匹配失败。 */
  public static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return ALL_BLANK_CHARS.matcher(value.trim()).replaceAll("");
  }

  public static String normalizeToNull(String value) {
    String normalized = normalize(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  public static boolean isChineseMaterialScrapHeader(String materialCode) {
    return "物料料号".equals(normalize(materialCode));
  }

  public static boolean isInvalidSequenceStatus(String sequenceStatus) {
    String normalized = normalize(sequenceStatus);
    if (!StringUtils.hasText(normalized)) {
      return false;
    }
    return INVALID_SEQUENCE_STATUSES.contains(normalized);
  }

  public static boolean isCurrentMappingCandidate(
      String materialCode, String recycleMaterialCode, String sequenceStatus) {
    return StringUtils.hasText(normalize(materialCode))
        && StringUtils.hasText(normalize(recycleMaterialCode))
        && !isInvalidSequenceStatus(sequenceStatus);
  }
}
