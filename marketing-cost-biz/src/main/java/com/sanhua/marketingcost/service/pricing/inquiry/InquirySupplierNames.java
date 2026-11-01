package com.sanhua.marketingcost.service.pricing.inquiry;

import java.util.*;

/** Resolve an informal inquiry name before applying the existing supplier-ratio price policy. */
public final class InquirySupplierNames {
  public record Supplier(String code, String name) {}

  public record Match(String code, String name, String basis) {}

  private InquirySupplierNames() {}

  public static Match resolve(
      String name, List<Supplier> materialSuppliers, List<Supplier> catalog) {
    String input = InquiryNames.normalize(name);
    if (input.isEmpty()) return new Match(null, "", "EMPTY");
    for (var pass :
        List.of(
            new Search(materialSuppliers, true),
            new Search(catalog, true),
            new Search(materialSuppliers, false),
            new Search(catalog, false))) {
      var selected = best(input, pass.suppliers(), pass.exact());
      if (selected != null) return selected;
    }
    return new Match(null, name, "NAME_ONLY");
  }

  private record Search(List<Supplier> suppliers, boolean exact) {}

  private static Match best(String input, List<Supplier> candidates, boolean exact) {
    int highest = 0;
    var matches = new TreeMap<String, Supplier>();
    for (var supplier : candidates) {
      if (supplier.code() == null || supplier.code().isBlank()) continue;
      String full = InquiryNames.normalize(supplier.name());
      int score = 0;
      if (input.equals(full) || input.equals(InquiryNames.normalize(supplier.code())))
        score = 10000;
      else if (!exact) {
        String left = core(input), right = core(full);
        if (!left.isBlank() && left.equals(right)) score = 9000;
        else if (Math.min(left.length(), right.length()) >= 2
            && (left.contains(right) || right.contains(left)))
          score =
              1000
                  + Math.min(left.length(), right.length())
                      * 1000
                      / Math.max(left.length(), right.length());
      }
      if (score > highest) {
        highest = score;
        matches.clear();
      }
      if (score > 0 && score == highest) matches.putIfAbsent(supplier.code(), supplier);
    }
    if (matches.size() > 1)
      throw new IllegalArgumentException(
          "供方简称无法唯一匹配：" + input + "，候选供应商=" + String.join("、", matches.keySet()));
    if (matches.isEmpty()) return null;
    var supplier = matches.firstEntry().getValue();
    return new Match(supplier.code(), supplier.name(), exact ? "EXACT" : "ABBREVIATION");
  }

  private static String core(String name) {
    for (String suffix : List.of("股份有限公司", "有限责任公司", "有限公司", "公司"))
      if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
    return name;
  }
}
