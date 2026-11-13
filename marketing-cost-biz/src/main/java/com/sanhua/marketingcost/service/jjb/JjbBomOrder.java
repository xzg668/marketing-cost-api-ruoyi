package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import java.util.*;

/** Orders saved material occurrences without loading today's BOM or changing financial sections. */
final class JjbBomOrder {
  private final Map<String, Integer> savedSequences = new HashMap<>();

  JjbBomOrder(List<QuoteEffectiveBomNodeResponse> nodes) {
    for (var node : nodes) if (node.nodePath() != null)
      savedSequences.put(normalize(node.nodePath()), node.sortSeq() == null ? 0 : node.sortSeq());
  }

  ArrayList<Row> apply(List<Row> rows) {
    var parentPaths = new HashMap<Long, List<String>>();
    for (var row : rows) if (material(row) && !"EXPLANATION".equals(row.kind()) && !row.paths().isEmpty())
      for (var id : row.partItemIds()) parentPaths.putIfAbsent(id, row.paths());
    var paths = new IdentityHashMap<Row, String>();
    for (var row : rows) if (material(row)) {
      var candidates = row.paths();
      if (candidates.isEmpty()) candidates = row.partItemIds().stream().map(parentPaths::get)
          .filter(Objects::nonNull).flatMap(List::stream).toList();
      paths.put(row, candidates.stream().filter(Objects::nonNull).map(JjbBomOrder::normalize)
          .filter(path -> !path.isBlank()).min(this::comparePaths).orElse(null));
    }
    var result = new ArrayList<>(rows);
    for (int start = 0; start < rows.size(); ) {
      int end = start + 1;
      while (end < rows.size() && Objects.equals(rows.get(start).section(), rows.get(end).section())) end++;
      var slots = new ArrayList<Integer>();
      var ordered = new ArrayList<Row>();
      for (int i = start; i < end; i++) if (material(rows.get(i))) {
        slots.add(i); ordered.add(rows.get(i));
      }
      // Stable ties retain parent/inline explanations and legacy rows with no saved path.
      ordered.sort(Comparator.comparing(paths::get, Comparator.nullsLast(this::comparePaths)));
      for (int i = 0; i < slots.size(); i++) result.set(slots.get(i), ordered.get(i));
      start = end;
    }
    return result;
  }

  private static boolean material(Row row) {
    return "PART".equals(row.kind()) || "HOUSEHOLD".equals(row.kind()) || "EXPLANATION".equals(row.kind());
  }

  private int comparePaths(String left, String right) {
    String[] a = left.split("/"), b = right.split("/");
    String prefix = "";
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      if (!a[i].equals(b[i])) {
        int sequence = Integer.compare(sequence(prefix + a[i], a[i]), sequence(prefix + b[i], b[i]));
        return sequence != 0 ? sequence : a[i].compareTo(b[i]);
      }
      prefix += a[i] + "/";
    }
    return Integer.compare(a.length, b.length);
  }

  private int sequence(String path, String segment) {
    var saved = savedSequences.get(path);
    if (saved != null) return saved;
    // Older snapshots encode the original sibling sequence as material@sequence@source-line.
    int at = segment.indexOf('@');
    if (at >= 0) {
      int end = segment.indexOf('@', at + 1);
      try { return Integer.parseInt(segment.substring(at + 1, end < 0 ? segment.length() : end)); }
      catch (NumberFormatException ignored) { /* Unknown legacy path: retain a deterministic tie order. */ }
    }
    return Integer.MAX_VALUE;
  }

  private static String normalize(String path) { return path.trim().replaceAll("^/+|/+$", ""); }
}
