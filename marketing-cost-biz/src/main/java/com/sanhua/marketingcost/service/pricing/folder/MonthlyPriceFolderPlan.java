package com.sanhua.marketingcost.service.pricing.folder;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 相同规则合并来源，不同规则全部保留；价格高低由适用月份的实际计算决定。 */
@Component
public final class MonthlyPriceFolderPlan {
  public List<Group> build(List<Candidate> candidates) {
    Map<String, List<Candidate>> byIdentity = new TreeMap<>();
    for (var candidate : candidates)
      byIdentity.computeIfAbsent(candidate.identity().identityKey(), ignored -> new ArrayList<>()).add(candidate);
    List<Group> groups = new ArrayList<>();
    for (var entry : byIdentity.entrySet()) {
      var rows = entry.getValue();
      rows.sort(Comparator.comparing(Candidate::fileName).thenComparing(Candidate::sheet)
          .thenComparingInt(Candidate::row).thenComparing(Candidate::id));
      var rules = representatives(rows);
      String status = rules.stream().allMatch(c -> "PUBLISHED".equals(c.sourceState())) ? "IMPORTED"
          : rules.stream().allMatch(c -> c.fileId() == null) ? "PREPARED" : "READY";
      groups.add(new Group(entry.getKey(), rows.getFirst().identity(), status, rules.size(), List.copyOf(rows)));
    }
    return List.copyOf(groups);
  }

  public List<Candidate> representatives(List<Candidate> candidates) {
    Map<String, List<Candidate>> byRule = new LinkedHashMap<>();
    for (var candidate : candidates) byRule.computeIfAbsent(candidate.signature(), ignored -> new ArrayList<>()).add(candidate);
    return byRule.values().stream().map(rows -> rows.stream().min(Comparator
        .comparingInt((Candidate c) -> "PUBLISHED".equals(c.sourceState()) ? 0 : "STAGED".equals(c.sourceState()) ? 1 : 2)
        .thenComparing(Candidate::fileName).thenComparing(Candidate::sheet).thenComparingInt(Candidate::row)).orElseThrow()).toList();
  }
}
