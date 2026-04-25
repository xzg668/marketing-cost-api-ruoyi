package com.sanhua.marketingcost.enums;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 价格类型枚举 —— 金标 Excel 共 6 桶价格来源。
 *
 * <p>历史只支持 FIXED / LINKED 两桶，本次扩展到 6 桶：
 * <ol>
 *   <li>FIXED         固定价 —— lp_price_fixed_item</li>
 *   <li>LINKED        联动价 —— lp_price_linked_calc_item（含公式）</li>
 *   <li>SETTLE        结算价 —— lp_price_settle_item（家用件结算）</li>
 *   <li>RANGE         区间价 —— lp_price_range_item（按重量区间取价）</li>
 *   <li>BOM_CALC      BOM 计算 —— 制造件递归 BOM 加工费 + 原材料</li>
 *   <li>RAW_BREAKDOWN 原材料拆解 —— lp_raw_material_breakdown（多种原料按比例）</li>
 * </ol>
 *
 * <p>形态白名单（决策放宽后）：
 * <ul>
 *   <li>采购件     → {FIXED, LINKED, RANGE, SETTLE}</li>
 *   <li>制造件     → {BOM_CALC, LINKED, FIXED}</li>
 *   <li>委外加工件 → {FIXED, SETTLE}</li>
 * </ul>
 */
public enum PriceTypeEnum {
  /** 固定价 */
  FIXED("固定价"),
  /** 联动价（含公式引擎） */
  LINKED("联动价"),
  /** 结算价（家用件） */
  SETTLE("结算价"),
  /** 区间价（按重量段） */
  RANGE("区间价"),
  /** BOM 计算（制造件） */
  BOM_CALC("BOM计算"),
  /** 原材料拆解 */
  RAW_BREAKDOWN("原材料拆解");

  /** 与数据库 lp_material_price_type.price_type 一致的中文文案 */
  private final String dbText;

  PriceTypeEnum(String dbText) {
    this.dbText = dbText;
  }

  public String getDbText() {
    return dbText;
  }

  /** 反查：从数据库文案恢复枚举值；找不到返回 empty */
  public static Optional<PriceTypeEnum> fromDbText(String dbText) {
    if (dbText == null) {
      return Optional.empty();
    }
    String trimmed = dbText.trim();
    for (PriceTypeEnum value : values()) {
      if (value.dbText.equals(trimmed)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  /**
   * 形态-价格类型白名单校验。返回该形态合法可用的价格类型集合。
   *
   * <p>用于 import 工具与 Router 服务在写入 / 查询时做一次性校验，避免脏数据。
   */
  public static Set<PriceTypeEnum> allowedFor(MaterialFormAttrEnum formAttr) {
    if (formAttr == null) {
      return EnumSet.noneOf(PriceTypeEnum.class);
    }
    return switch (formAttr) {
      case PURCHASED -> EnumSet.of(FIXED, LINKED, RANGE, SETTLE);
      case MANUFACTURED -> EnumSet.of(BOM_CALC, LINKED, FIXED);
      case OUTSOURCED -> EnumSet.of(FIXED, SETTLE);
    };
  }
}
