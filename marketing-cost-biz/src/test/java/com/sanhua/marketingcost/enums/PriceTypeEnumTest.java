package com.sanhua.marketingcost.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T01 单测：PriceTypeEnum 4 桶 + 双别名兼容 + 形态白名单。
 *
 * <p>验收点：
 * <ul>
 *   <li>枚举值精确 4 个：FIXED / LINKED / RANGE / MAKE</li>
 *   <li>fromDbText 4 个标准 dbText 命中</li>
 *   <li>fromDbText 双别名兼容：固定采购价/结算价/家用结算价 → FIXED；BOM计算/原材料联动/原材料拆解 → MAKE</li>
 *   <li>fromDbText null/空/未知文案 → empty</li>
 *   <li>allowedFor：采购件 → {FIXED,LINKED,RANGE}；制造件 → {MAKE,LINKED,FIXED}；委外件 → {FIXED}</li>
 * </ul>
 */
class PriceTypeEnumTest {

  @Test
  void enumValues_should_be_exactly_4() {
    PriceTypeEnum[] values = PriceTypeEnum.values();
    assertEquals(4, values.length, "v1 起 PriceTypeEnum 必须只有 4 个值");
    assertEquals("FIXED", PriceTypeEnum.FIXED.name());
    assertEquals("LINKED", PriceTypeEnum.LINKED.name());
    assertEquals("RANGE", PriceTypeEnum.RANGE.name());
    assertEquals("MAKE", PriceTypeEnum.MAKE.name());
  }

  @Test
  void getDbText_should_return_chinese_label() {
    assertEquals("固定价", PriceTypeEnum.FIXED.getDbText());
    assertEquals("联动价", PriceTypeEnum.LINKED.getDbText());
    assertEquals("区间价", PriceTypeEnum.RANGE.getDbText());
    assertEquals("自制件", PriceTypeEnum.MAKE.getDbText());
  }

  @Test
  void fromDbText_should_match_4_canonical_labels() {
    assertEquals(Optional.of(PriceTypeEnum.FIXED), PriceTypeEnum.fromDbText("固定价"));
    assertEquals(Optional.of(PriceTypeEnum.LINKED), PriceTypeEnum.fromDbText("联动价"));
    assertEquals(Optional.of(PriceTypeEnum.RANGE), PriceTypeEnum.fromDbText("区间价"));
    assertEquals(Optional.of(PriceTypeEnum.MAKE), PriceTypeEnum.fromDbText("自制件"));
  }

  @Test
  void fromDbText_FIXED_aliases() {
    // FIXED 的双别名兼容（V46/V47 历史数据 + Excel 业务标签）
    assertEquals(Optional.of(PriceTypeEnum.FIXED), PriceTypeEnum.fromDbText("固定采购价"));
    assertEquals(Optional.of(PriceTypeEnum.FIXED), PriceTypeEnum.fromDbText("结算价"));
    assertEquals(Optional.of(PriceTypeEnum.FIXED), PriceTypeEnum.fromDbText("家用结算价"));
  }

  @Test
  void fromDbText_MAKE_aliases() {
    // MAKE 的双别名兼容（原 BOM_CALC + 原 RAW_BREAKDOWN + Excel 业务标签）
    assertEquals(Optional.of(PriceTypeEnum.MAKE), PriceTypeEnum.fromDbText("BOM计算"));
    assertEquals(Optional.of(PriceTypeEnum.MAKE), PriceTypeEnum.fromDbText("原材料联动"));
    assertEquals(Optional.of(PriceTypeEnum.MAKE), PriceTypeEnum.fromDbText("原材料拆解"));
  }

  @Test
  void fromDbText_should_trim_whitespace() {
    assertEquals(Optional.of(PriceTypeEnum.FIXED), PriceTypeEnum.fromDbText("  固定价  "));
    assertEquals(Optional.of(PriceTypeEnum.MAKE), PriceTypeEnum.fromDbText("\t自制件\n"));
  }

  @Test
  void fromDbText_should_return_empty_for_null_or_unknown() {
    assertEquals(Optional.empty(), PriceTypeEnum.fromDbText(null));
    assertEquals(Optional.empty(), PriceTypeEnum.fromDbText(""));
    assertEquals(Optional.empty(), PriceTypeEnum.fromDbText("   "));
    assertEquals(Optional.empty(), PriceTypeEnum.fromDbText("未知类型"));
    assertEquals(Optional.empty(), PriceTypeEnum.fromDbText("SETTLE"));
  }

  @Test
  void allowedFor_purchased_should_be_fixed_linked_range() {
    Set<PriceTypeEnum> allowed = PriceTypeEnum.allowedFor(MaterialFormAttrEnum.PURCHASED);
    assertEquals(EnumSet.of(PriceTypeEnum.FIXED, PriceTypeEnum.LINKED, PriceTypeEnum.RANGE), allowed);
    assertTrue(allowed.contains(PriceTypeEnum.FIXED));
    assertFalse(allowed.contains(PriceTypeEnum.MAKE), "采购件不应允许 MAKE");
  }

  @Test
  void allowedFor_manufactured_should_be_make_linked_fixed() {
    Set<PriceTypeEnum> allowed = PriceTypeEnum.allowedFor(MaterialFormAttrEnum.MANUFACTURED);
    assertEquals(EnumSet.of(PriceTypeEnum.MAKE, PriceTypeEnum.LINKED, PriceTypeEnum.FIXED), allowed);
    assertTrue(allowed.contains(PriceTypeEnum.MAKE));
    assertFalse(allowed.contains(PriceTypeEnum.RANGE), "制造件不应允许 RANGE");
  }

  @Test
  void allowedFor_outsourced_should_be_fixed_only() {
    Set<PriceTypeEnum> allowed = PriceTypeEnum.allowedFor(MaterialFormAttrEnum.OUTSOURCED);
    assertEquals(EnumSet.of(PriceTypeEnum.FIXED), allowed);
    assertEquals(1, allowed.size(), "委外件 v1 仅允许 FIXED");
  }

  @Test
  void allowedFor_null_should_be_empty() {
    assertTrue(PriceTypeEnum.allowedFor(null).isEmpty());
  }
}
