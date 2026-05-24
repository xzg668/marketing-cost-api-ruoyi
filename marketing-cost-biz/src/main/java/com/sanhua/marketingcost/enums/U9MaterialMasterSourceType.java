package com.sanhua.marketingcost.enums;

import java.util.Arrays;
import org.springframework.util.StringUtils;

/** U9 料品主档接入来源，和 lp_material_master_raw.source_type 保持一致。 */
public enum U9MaterialMasterSourceType {
  EXCEL("EXCEL"),
  API("API"),
  MQ("MQ"),
  SCHEDULE("SCHEDULE");

  private final String code;

  U9MaterialMasterSourceType(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  public static U9MaterialMasterSourceType fromCode(String code) {
    if (!StringUtils.hasText(code)) {
      return EXCEL;
    }
    return Arrays.stream(values())
        .filter(type -> type.code.equalsIgnoreCase(code.trim()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的 U9 料品主档来源: " + code));
  }
}
