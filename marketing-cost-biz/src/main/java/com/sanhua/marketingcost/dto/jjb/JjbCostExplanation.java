package com.sanhua.marketingcost.dto.jjb;

import java.util.List;

/** Read-only explanation of the selected saved version, shared by API and Excel. */
public record JjbCostExplanation(List<Term> totalItems, List<Material> materials, String note) {
  public JjbCostExplanation {
    totalItems = List.copyOf(totalItems);
    materials = List.copyOf(materials);
  }
  public record Term(String name, String formula, String amount) {}
  public record Price(String materialCode, String source, String baseFactors,
      String customerFactors, String formula) {}
  public record Material(String code, String name, String quantity, String unit,
      List<Price> prices, String formula, String financialAmount, String customerAmount,
      String difference) {
    public Material { prices = List.copyOf(prices); }
  }
}
