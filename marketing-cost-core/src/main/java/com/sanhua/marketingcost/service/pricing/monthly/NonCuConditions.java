package com.sanhua.marketingcost.service.pricing.monthly;

/** Known non-Cu request conditions; an empty request is distinct from an ANY domain. */
public record NonCuConditions(BoardTerms boardTerms) {
  public static NonCuConditions empty() {
    return new NonCuConditions(null);
  }
}
