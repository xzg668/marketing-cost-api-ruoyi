package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Solder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 页面和保存只使用 kg／单件产品；单位未知时不能把数量当成重量。 */
public final class TechnicalDataSolderRules {
  private TechnicalDataSolderRules() {}

  public static boolean eligible(String category) {
    return category != null && category.trim().startsWith("18181") && !"181811435".equals(category.trim());
  }

  public static BigDecimal toKg(BigDecimal quantity, String unit) {
    if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("参考焊料缺少大于 0 的每产品累计用量");
    return switch (Objects.toString(unit, "").trim().toLowerCase(Locale.ROOT)) {
      case "kg", "千克", "公斤" -> quantity;
      case "g", "克" -> quantity.movePointLeft(3);
      default -> throw new IllegalArgumentException("焊料单位“" + Objects.toString(unit, "未提供") + "”不能换算为 kg，请核实来源单位");
    };
  }

  public static BigDecimal quantity(BigDecimal value) {
    if (value == null) return null;
    var normalized = value.stripTrailingZeros();
    if (value.signum() <= 0 || normalized.scale() > 11 || normalized.precision() - normalized.scale() > 12) {
      throw new IllegalArgumentException("焊料用量须大于 0，整数不超过 12 位、小数不超过 11 位");
    }
    // 原始 BOM 最多 8 位小数；g 转 kg 保留额外 3 位，不能把小用量舍入为零。
    return normalized;
  }

  public static List<String> validate(Solder content) {
    if (content == null || !Set.of("REFERENCE", "MANUAL").contains(Objects.toString(content.entryMode(), ""))) {
      return List.of("请选择参考焊料或新增焊料，并保存明细");
    }
    var issues = new ArrayList<String>();
    boolean reference = "REFERENCE".equals(content.entryMode());
    if (reference && (content.reference() == null || content.reference().fingerprint() == null)) issues.add("参考成品来源不完整");
    if (!reference && content.reference() != null) issues.add("新增焊料不能带入参考成品来源");
    if (content.items() == null || content.items().isEmpty()) return List.of("请至少填写一条焊料明细");
    var keys = new HashSet<String>();
    var materials = new HashSet<String>();
    for (int index = 0; index < content.items().size(); index++) {
      var item = content.items().get(index);
      String prefix = "第 " + (index + 1) + " 行：";
      if (item == null || item.evidence() == null || item.evidence().material() == null) { issues.add(prefix + "缺少有效料品档案"); continue; }
      var material = item.evidence().material();
      if (item.itemKey() == null || !keys.add(item.itemKey())) issues.add(prefix + "来源节点重复");
      if (!reference && !materials.add(item.materialNo())) issues.add(prefix + "焊料料号重复，请合并用量");
      if (!Objects.equals(item.materialNo(), material.materialNo()) || !Objects.equals(item.drawingNo(), material.drawingNo())
          || !eligible(material.mainCategoryCode()) || material.fingerprint() == null) issues.add(prefix + "料号、图号或分类与档案不一致");
      if (!"kg".equals(item.unit())) issues.add(prefix + "本次用量单位必须为 kg");
      if (reference != (item.evidence().bom() != null)) issues.add(prefix + "明细来源与录入方式不一致");
      if (item.quantityPerProduct() == null) issues.add(prefix + "请填写用量");
      else try { quantity(item.quantityPerProduct()); } catch (IllegalArgumentException exception) { issues.add(prefix + exception.getMessage()); }
      try { toKg(BigDecimal.ONE, material.unit()); } catch (IllegalArgumentException exception) { issues.add(prefix + exception.getMessage()); }
    }
    return List.copyOf(issues);
  }
}
