package com.sanhua.marketingcost.service.pricing.monthly;

/** Known non-Cu request conditions; an empty request is distinct from an ANY domain. */
public record NonCuConditions(BoardTerms boardTerms,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String materialShapeScope) {
  public NonCuConditions(BoardTerms boardTerms) { this(boardTerms, null); }
  public NonCuConditions {
    if (materialShapeScope != null && (materialShapeScope.isBlank() || materialShapeScope.length() > 160))
      throw new IllegalArgumentException("物料形态取价范围无效");
  }
  public NonCuConditions withMaterialShapeScope(String scope) {
    return new NonCuConditions(boardTerms, scope);
  }
  public static NonCuConditions empty() {
    return new NonCuConditions(null);
  }
}
