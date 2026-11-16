package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import java.util.*;

/** Packaging labels come from the saved material occurrence, never today's master or prices. */
final class JjbPackagingIdentity {
  record Identity(String codes, String specifications) {}
  private record Material(String code, String specification) {}

  static Identity from(List<Row> components, List<QuoteEffectiveBomNodeResponse> savedBom) {
    var materials = new LinkedHashSet<Material>();
    for (var component : components) {
      if (component.code() == null || component.code().isBlank()) continue;
      var candidates = savedBom.stream().filter(node -> component.code().equals(node.materialCode())).toList();
      var exact = candidates.stream().filter(node -> component.paths().stream()
          .anyMatch(path -> normalize(path).equals(normalize(node.nodePath())))).toList();
      var specs = (exact.isEmpty() ? candidates : exact).stream()
          .map(QuoteEffectiveBomNodeResponse::materialSpec).filter(Objects::nonNull)
          .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
      // An old version may lack a saved BOM or have ambiguous material occurrences.
      materials.add(new Material(component.code(), specs.size() == 1 ? specs.getFirst() : ""));
    }
    if (materials.isEmpty()) return new Identity(null, null);
    return new Identity(String.join("\n", materials.stream().map(Material::code).toList()),
        String.join("\n", materials.stream().map(Material::specification).toList()));
  }

  private static String normalize(String path) {
    return path == null ? "" : path.trim().replaceAll("^/+|/+$", "");
  }
}
