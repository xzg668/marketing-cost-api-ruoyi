package com.sanhua.marketingcost.service.pricing;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import java.util.*;
import java.util.function.*;

/** 同一次有效修订可包含多套并行规则；旧修订完整保留，但不与当前修订混算。 */
public final class OriginalRuleGroups {
  private static final ObjectMapper JSON = new ObjectMapper();
  private OriginalRuleGroups() {}
  public static int revision(PriceLinkedItem row) { return revision(row.getSourceInputSnapshotJson(), row.getRuleRevision()); }
  public static int revision(PriceRangeFactorRule row) { return revision(row.getSourceInputSnapshotJson(), row.getVersionNo()); }
  private static int revision(String snapshot, Integer physicalRevision) {
    int fallback = Objects.requireNonNullElse(physicalRevision, 0);
    if (snapshot == null || snapshot.isBlank()) return fallback;
    try (var parser = JSON.createParser(snapshot)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) return fallback;
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String name = parser.currentName(); parser.nextToken();
        if (name.equals("ruleSetRevision")) return parser.getIntValue();
        parser.skipChildren();
      }
    } catch (Exception malformedLegacySnapshot) {
      // 此处仅选版本；被选中的执行快照仍严格校验，历史坏快照不能阻断有效的新修订。
    }
    return fallback;
  }
  public static String withRevision(String snapshot, int revision) {
    if (revision < 1) throw new IllegalArgumentException("规则组修订无效");
    try {
      var source = JSON.readTree(snapshot);
      if (!source.isObject()) throw new IllegalArgumentException("规则来源快照必须是对象");
      var result = JSON.createObjectNode(); result.put("ruleSetRevision", revision);
      source.fields().forEachRemaining(entry -> { if (!entry.getKey().equals("ruleSetRevision")) result.set(entry.getKey(), entry.getValue()); });
      return JSON.writeValueAsString(result);
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("无法保存规则组来源", failure); }
  }
  public static List<PriceRangeFactorRule> ranges(Collection<PriceRangeFactorRule> rows) {
    return current(rows, row -> row.getRuleIdentityKey() == null ? "LEGACY:" + row.getId() : row.getRuleIdentityKey(), OriginalRuleGroups::revision);
  }
  public static <T> List<T> current(Collection<T> rows, Function<T, String> identity, ToIntFunction<T> revision) {
    Map<String, List<T>> groups = new LinkedHashMap<>();
    for (var row : rows) {
      var group = groups.computeIfAbsent(identity.apply(row), ignored -> new ArrayList<>());
      if (!group.isEmpty() && revision.applyAsInt(row) > revision.applyAsInt(group.getFirst())) group.clear();
      if (group.isEmpty() || revision.applyAsInt(row) == revision.applyAsInt(group.getFirst())) group.add(row);
    }
    return groups.values().stream().flatMap(List::stream).toList();
  }
}
