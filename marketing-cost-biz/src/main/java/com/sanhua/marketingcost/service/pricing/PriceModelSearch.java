package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Literal, case-insensitive model/specification search. No pricing or source selection changes. */
public final class PriceModelSearch {
  private PriceModelSearch() {}

  public static String normalize(String value) {
    if (value == null || value.isBlank()) return "";
    String result = value.strip();
    if (result.length() > 255) throw new IllegalArgumentException("型号查询不能超过255个字符");
    return result.toLowerCase(Locale.ROOT);
  }

  public static boolean matches(String model, String value) {
    String key = normalize(model);
    return key.isEmpty() || (value != null && value.toLowerCase(Locale.ROOT).contains(key));
  }

  public static String condition(String parameter, String... columns) {
    return Arrays.stream(columns).map(column -> "LOCATE(" + parameter
        + ",LOWER(COALESCE(" + column + ",'')))>0").collect(Collectors.joining(" OR ", "(", ")"));
  }

  public static <T> void apply(LambdaQueryWrapper<T> query, String model, String... columns) {
    String key = normalize(model);
    if (!key.isEmpty()) query.apply(condition("{0}", columns), key);
  }

  /** For ledgers without saved specifications, match only the row's own organization. */
  public static String masterCondition(String materialColumn, String organizationColumn, String parameter) {
    return "EXISTS (SELECT 1 FROM lp_material_master_raw model_master WHERE model_master.active_flag=1"
        + " AND CONVERT(model_master.material_code USING utf8mb4) COLLATE utf8mb4_unicode_ci=CONVERT("
        + materialColumn + " USING utf8mb4) COLLATE utf8mb4_unicode_ci"
        + " AND CONVERT(model_master.organization_code USING utf8mb4) COLLATE utf8mb4_unicode_ci=CONVERT("
        + organizationColumn + " USING utf8mb4) COLLATE utf8mb4_unicode_ci AND "
        + condition(parameter, "model_master.material_model", "model_master.material_spec", "model_master.drawing_no") + ")";
  }

  public static <T> void applyMaster(LambdaQueryWrapper<T> query, String model, String materialColumn, String organizationColumn) {
    String key = normalize(model);
    if (!key.isEmpty()) query.apply(masterCondition(materialColumn, organizationColumn, "{0}"), key);
  }
}
