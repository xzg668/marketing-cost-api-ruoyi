package com.sanhua.marketingcost.service.technicaldata;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 模块顺序属于业务契约，页面、提交快照和校验共用此顺序。 */
public enum TechnicalDataModuleType {
  PROFILE("产品资料"),
  DRAWING_BOM("电子图库明细表"),
  MANUFACTURING("制造件原材料"),
  PACKAGE("包装"),
  AUXILIARY("辅料"),
  SOLDER("焊料"),
  SALARY("工资"),
  NET_LOSS("净损失率"),
  PRICE("价格");

  private final String displayName;

  TechnicalDataModuleType(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() { return displayName; }

  private static final Set<String> LEGACY_CODES = Set.of(
      "PROFILE", "PACKAGE", "AUXILIARY", "SALARY");
  private static final List<String> ORDERED_CODES = Arrays.stream(values())
      .map(Enum::name).toList();
  private static final Set<String> CODES = Set.copyOf(ORDERED_CODES);

  public static List<String> orderedCodes() {
    return ORDERED_CODES;
  }

  public static Set<String> codes() {
    return CODES;
  }

  /** 仅用于读取旧四模块版本，不将旧审批扩展成九模块审批。 */
  public static Set<String> codesForVersion(Integer schemaVersion) {
    return schemaVersion == null || schemaVersion == 1 ? LEGACY_CODES : CODES;
  }

  public static int orderOf(String code) {
    return valueOf(code).ordinal();
  }
}
