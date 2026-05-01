package com.sanhua.marketingcost.enums;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 价格类型枚举 —— v1 简化为 4 桶（FIXED / LINKED / RANGE / MAKE）。
 *
 * <p>历史变更（V48+）：6 桶 → 4 桶
 * <ul>
 *   <li>原 SETTLE 结算价 → 合并到 FIXED（家用结算价归 lp_price_fixed_item.source_type=SETTLE）</li>
 *   <li>原 BOM_CALC → 改名 MAKE（自制件按 lp_make_part_spec 配方算价）</li>
 *   <li>原 RAW_BREAKDOWN → 合并到 MAKE（原材料拆解本质是自制件配方公式的一种）</li>
 * </ul>
 *
 * <p>4 桶语义：
 * <ol>
 *   <li>FIXED  固定价 —— 一口价（lp_price_fixed_item，含 PURCHASE/SETTLE 两种 source_type）</li>
 *   <li>LINKED 联动价 —— 公式 + 月度基价实时算（lp_price_linked_item + lp_finance_base_price）</li>
 *   <li>RANGE  区间价 —— 按数量段取价（lp_price_range_item）</li>
 *   <li>MAKE   自制件 —— 配方算价（lp_make_part_spec：原料 × 毛重 - 废料 × 边角 + 加工费）</li>
 * </ol>
 *
 * <p>形态白名单（决定该物料形态合法的取价桶）：
 * <ul>
 *   <li>采购件     → {FIXED, LINKED, RANGE}</li>
 *   <li>制造件     → {MAKE, LINKED, FIXED}</li>
 *   <li>委外加工件 → {FIXED}</li>
 * </ul>
 */
public enum PriceTypeEnum {
  /** 固定价 */
  FIXED("固定价"),
  /** 联动价（含公式引擎 + 月度基价） */
  LINKED("联动价"),
  /** 区间价（按数量段） */
  RANGE("区间价"),
  /** 自制件（配方算价） */
  MAKE("自制件");

  /** 与 lp_material_price_type.price_type 一致的中文文案 */
  private final String dbText;

  PriceTypeEnum(String dbText) {
    this.dbText = dbText;
  }

  public String getDbText() {
    return dbText;
  }

  /**
   * 反查：字符串 → 枚举。
   *
   * <p>支持双别名兼容（V48 起 lp_material_price_type 实际可能仍含历史值，需平滑过渡）：
   * <ul>
   *   <li>"固定价" / "固定采购价" / "结算价" / "家用结算价" → FIXED</li>
   *   <li>"联动价" → LINKED</li>
   *   <li>"区间价" → RANGE</li>
   *   <li>"自制件" / "BOM计算" / "原材料联动" / "原材料拆解" → MAKE</li>
   * </ul>
   *
   * <p>找不到匹配项返回 {@code Optional.empty()}（调用方应记 WARN 并标红）。
   */
  public static Optional<PriceTypeEnum> fromDbText(String dbText) {
    if (dbText == null) {
      return Optional.empty();
    }
    String s = dbText.trim();
    if (s.isEmpty()) {
      return Optional.empty();
    }
    return switch (s) {
      case "固定价", "固定采购价", "结算价", "家用结算价" -> Optional.of(FIXED);
      case "联动价" -> Optional.of(LINKED);
      case "区间价" -> Optional.of(RANGE);
      case "自制件", "BOM计算", "原材料联动", "原材料拆解" -> Optional.of(MAKE);
      default -> Optional.empty();
    };
  }

  /**
   * 形态-价格类型白名单：返回该物料形态合法可用的取价桶集合。
   *
   * <p>用于 import 工具与 Router 服务在写入 / 查询时做一次性校验，避免脏数据
   * （如把"采购件"路由到 MAKE 桶 → 不合法）。
   */
  public static Set<PriceTypeEnum> allowedFor(MaterialFormAttrEnum formAttr) {
    if (formAttr == null) {
      return EnumSet.noneOf(PriceTypeEnum.class);
    }
    return switch (formAttr) {
      case PURCHASED -> EnumSet.of(FIXED, LINKED, RANGE);
      case MANUFACTURED -> EnumSet.of(MAKE, LINKED, FIXED);
      case OUTSOURCED -> EnumSet.of(FIXED);
    };
  }
}
