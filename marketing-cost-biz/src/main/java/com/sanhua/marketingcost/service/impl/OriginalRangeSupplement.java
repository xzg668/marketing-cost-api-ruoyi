package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Band;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Bounds;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.ParsedWorkbook;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.RowError;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Rule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 补充档位仅修订所选业务身份，已有有效档位的报价和税口径保持。 */
final class OriginalRangeSupplement {
  private OriginalRangeSupplement() {}

  static ParsedWorkbook prepare(ParsedWorkbook uploaded, List<Rule> originals) {
    var rows = new LinkedHashMap<String, Rule>();
    for (Rule rule : originals)
      if (rows.putIfAbsent(sourceRow(rule), rule) != null)
        throw new IllegalArgumentException("当前原稿的来源行重复，无法确认补充范围");
    var errors = new ArrayList<>(uploaded.errors().stream()
        .filter(error -> error.row() == null || rows.containsKey(error.sheet() + "!" + error.row()))
        .toList());
    var incoming = new LinkedHashMap<String, Rule>();
    uploaded.rules().forEach(rule -> incoming.put(sourceRow(rule), rule));
    var revised = new ArrayList<Rule>();
    int additions = 0;
    for (Rule original : rows.values()) {
      try {
        var changes = supplement(original, incoming.get(sourceRow(original)));
        revised.add(changes.rule());
        additions += changes.added();
      } catch (IllegalArgumentException failure) {
        errors.add(new RowError(original.sheet(), original.row(), original.identity().materialCode(),
            failure.getMessage()));
      }
    }
    if (errors.isEmpty() && additions == 0)
      errors.add(new RowError(null, null, null, "未找到新增的有效档位，请填写新档的对应价格"));
    var notices = uploaded.notices().stream()
        .filter(notice -> rows.containsKey(notice.sheet() + "!" + notice.row()))
        .toList();
    return new ParsedWorkbook(List.copyOf(revised), List.copyOf(errors), notices);
  }

  private static Changes supplement(Rule original, Rule uploaded) {
    if (uploaded == null || !original.identity().equals(uploaded.identity())
        || !Objects.equals(original.materialName(), uploaded.materialName())
        || !Objects.equals(original.mainPriceSource().rawValue(), uploaded.mainPriceSource().rawValue())
        || original.taxIncluded() != uploaded.taxIncluded()
        || !original.factorKey().equals(uploaded.factorKey())
        || original.factorUnit() != uploaded.factorUnit())
      throw new IllegalArgumentException("请保留原稿主表的行位置、料号、供应商、规格、单价和税口径");

    Map<Bounds, Band> oldBands = bandsByBounds(original);
    Map<Bounds, Band> newBands = bandsByBounds(uploaded);
    var additions = new ArrayList<Band>();
    for (var entry : oldBands.entrySet()) {
      Band old = entry.getValue();
      Band next = newBands.get(entry.getKey());
      if (next == null)
        throw new IllegalArgumentException("请保留原档位：" + old.header().rawValue());
      if (old.price() == null && blank(old) && !blank(next)) {
        if (next.price() == null)
          throw new IllegalArgumentException("补充档位的价格不可用：" + next.header().rawValue());
        additions.add(next);
      } else if (!samePrice(old.price(), next.price())) {
        throw new IllegalArgumentException("已有档位价格发生变化：" + old.header().rawValue());
      }
    }
    var retained = new ArrayList<Band>();
    for (var entry : newBands.entrySet()) {
      Band band = entry.getValue();
      if (!oldBands.containsKey(entry.getKey())) {
        if (blank(band)) continue;
        if (band.price() == null)
          throw new IllegalArgumentException("新增档位的价格不可用：" + band.header().rawValue());
        additions.add(band);
      }
      retained.add(band);
    }
    for (Band added : additions)
      if (retained.stream().anyMatch(band -> band != added && overlaps(band.bounds(), added.bounds())))
        throw new IllegalArgumentException("新增档位与其他档位重叠：" + added.header().rawValue());
    return new Changes(withBands(uploaded, retained), additions.size());
  }

  static Rule withBands(Rule rule, List<Band> bands) {
    return new Rule(
        rule.identity(), rule.materialName(), rule.sheet(), rule.row(), rule.mainPriceSource(),
        rule.taxIncluded(), rule.factorKey(), rule.factorUnit(), rule.matrixSheet(), rule.matrixRow(),
        rule.matrixSpecification(), rule.matrixSupplier(), rule.bindingBasis(), List.copyOf(bands),
        rule.extrapolationSources());
  }

  private static Map<Bounds, Band> bandsByBounds(Rule rule) {
    var result = new LinkedHashMap<Bounds, Band>();
    for (Band band : rule.bands()) {
      var key = normalize(band.bounds());
      if (result.putIfAbsent(key, band) != null)
        throw new IllegalArgumentException("档位边界重复：" + band.header().rawValue());
    }
    return result;
  }

  static Bounds normalize(Bounds bounds) {
    return new Bounds(bounds.low().stripTrailingZeros(), bounds.high().stripTrailingZeros(),
        bounds.lowerInclusive(), bounds.upperInclusive());
  }

  static boolean blank(Band band) {
    return band.source() == null
        || band.source().formula() == null && band.source().rawValue().isBlank();
  }

  private static boolean samePrice(BigDecimal first, BigDecimal second) {
    return first == null ? second == null : second != null && first.compareTo(second) == 0;
  }

  static boolean overlaps(Bounds first, Bounds second) {
    int a = first.high().compareTo(second.low());
    int b = second.high().compareTo(first.low());
    return (a > 0 || a == 0 && first.upperInclusive() && second.lowerInclusive())
        && (b > 0 || b == 0 && second.upperInclusive() && first.lowerInclusive());
  }

  private static String sourceRow(Rule rule) {
    return rule.sheet() + "!" + rule.row();
  }

  private record Changes(Rule rule, int added) {}
}
