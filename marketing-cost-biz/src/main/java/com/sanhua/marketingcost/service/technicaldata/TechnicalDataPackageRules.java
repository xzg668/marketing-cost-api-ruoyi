package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Packaging;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 包装保存原始母子用量；每产品用量只在消费边界相乘一次。 */
public final class TechnicalDataPackageRules {
  public static final String PARENT_UNIT = "包装组件/件产品";
  private TechnicalDataPackageRules() {}

  public static List<String> validate(Packaging packaging, List<QuoteTechPackageItem> items) {
    var issues = new ArrayList<String>();
    if (packaging == null || packaging.entryMode() == null) return List.of("请重新确认包装方式及母件用量，旧资料保留供对照");
    if (!"MANUAL".equals(packaging.entryMode()) && !"REFERENCE".equals(packaging.entryMode())) issues.add("包装填写方式无效");
    if (!positive(packaging.parentQuantity())) issues.add("请填写大于 0 的母件用量");
    if (!PARENT_UNIT.equals(packaging.parentQuantityUnit())) issues.add("母件用量单位不明确");
    if ("REFERENCE".equals(packaging.entryMode()) && (packaging.source() == null
        || !Objects.equals(packaging.referenceMaterialNo(), packaging.source().topProductCode())
        || !Objects.equals(packaging.parentMaterialNo(), packaging.source().parentMaterialNo()))) issues.add("请选择明确的包装来源");
    if (items == null || items.isEmpty()) issues.add("请至少填写一条包装子件");
    var keys = new HashSet<String>();
    if (items != null) for (var item : items) {
      String prefix = "第 " + item.getLineNo() + " 行";
      if (item.getComponentMaterialNo() == null || item.getComponentMaterialNo().isBlank()) issues.add(prefix + "缺少料号或型号");
      if (item.getComponentName() == null || item.getComponentName().isBlank()) issues.add(prefix + "缺少名称");
      if (!positive(item.getQuantity())) issues.add(prefix + "子件用量必须大于 0");
      if (item.getOriginalUnit() == null || item.getOriginalUnit().isBlank()) issues.add(prefix + "缺少单位");
      if (item.getStandardQuantity() == null || item.getQuantity().compareTo(item.getStandardQuantity()) != 0
          || !Objects.equals(item.getOriginalUnit(), item.getStandardUnit())
          || item.getConversionFactor() == null || item.getConversionFactor().compareTo(BigDecimal.ONE) != 0) issues.add(prefix + "子件用量不得预乘母件用量");
      if (!keys.add(Objects.toString(item.getSourceReferenceId(), "MANUAL:" + item.getComponentMaterialNo()))) issues.add(prefix + "重复选择同一子件来源");
    }
    return List.copyOf(issues);
  }

  public static BigDecimal perProduct(Packaging packaging, QuoteTechPackageItem item) {
    if (!positive(packaging.parentQuantity()) || !positive(item.getQuantity())) throw new IllegalArgumentException("母子用量不完整，不能计算每产品用量");
    return packaging.parentQuantity().multiply(item.getQuantity());
  }

  public static BigDecimal quantity(BigDecimal value, String label, boolean nullable) {
    if (value == null && nullable) return null;
    if (!positive(value)) throw new IllegalArgumentException(label + "必须大于 0");
    if (value.stripTrailingZeros().scale() > 8 || value.precision() - value.scale() > 12) throw new IllegalArgumentException(label + "最多 12 位整数和 8 位小数");
    return value;
  }
  private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
