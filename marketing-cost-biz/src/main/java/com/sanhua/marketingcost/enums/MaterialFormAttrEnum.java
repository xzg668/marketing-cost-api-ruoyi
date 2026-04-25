package com.sanhua.marketingcost.enums;

import java.util.Optional;

/**
 * 物料形态属性 —— Excel 见机表3 中的"形态属性"列。
 *
 * <p>3 态模型（采购件 / 制造件 / 委外加工件），决定走哪一组 priceType 白名单，
 * 也决定后续走 BOM 计算 / 原材料拆解等扩展路径。
 *
 * <p>Excel 实际文案 ↔ 系统枚举映射：
 * <ul>
 *   <li>"采购"        → PURCHASED（采购件）</li>
 *   <li>"自制"        → MANUFACTURED（制造件）</li>
 *   <li>"原材料联动"   → MANUFACTURED（制造件，走原材料拆解 / 联动）</li>
 *   <li>"联动"        → 形态由 lp_material_price_type.material_shape 决定，不在此简单映射</li>
 *   <li>"家用结算价"   → OUTSOURCED（委外加工件，按结算价模板）</li>
 * </ul>
 */
public enum MaterialFormAttrEnum {
  /** 采购件 */
  PURCHASED("采购件"),
  /** 制造件 */
  MANUFACTURED("制造件"),
  /** 委外加工件 */
  OUTSOURCED("委外加工件");

  /** 与数据库 lp_material_price_type.material_shape 一致的中文文案 */
  private final String dbText;

  MaterialFormAttrEnum(String dbText) {
    this.dbText = dbText;
  }

  public String getDbText() {
    return dbText;
  }

  /** 反查：从数据库文案恢复枚举值；找不到返回 empty（让调用方决定降级行为） */
  public static Optional<MaterialFormAttrEnum> fromDbText(String dbText) {
    if (dbText == null) {
      return Optional.empty();
    }
    String trimmed = dbText.trim();
    for (MaterialFormAttrEnum value : values()) {
      if (value.dbText.equals(trimmed)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
