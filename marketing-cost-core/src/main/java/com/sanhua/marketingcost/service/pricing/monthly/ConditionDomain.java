package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A finite set or its complement over the explicitly supported non-Cu terms. */
public record ConditionDomain(Mode mode, Set<NonCuConditions> values,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String materialShapeScope) {

  public ConditionDomain(Mode mode, Set<NonCuConditions> values) { this(mode, values, null); }

  public enum Mode { ANY, EXACT, EXCLUDING }

  public ConditionDomain {
    Objects.requireNonNull(mode, "Condition mode is required");
    values = Set.copyOf(values);
    for (var value : values) if (!Objects.equals(materialShapeScope, value.materialShapeScope()))
      throw new IllegalArgumentException("价格适用条件混入其他形态范围");
    if (mode == Mode.ANY && !values.isEmpty()) {
      throw new IllegalArgumentException("ANY cannot also restrict explicit condition values");
    }
    if (mode == Mode.EXACT && values.isEmpty()) {
      throw new IllegalArgumentException("An exact condition domain must contain a value");
    }
    if (mode == Mode.EXCLUDING && values.isEmpty()) mode = Mode.ANY;
  }

  public static ConditionDomain any() {
    return new ConditionDomain(Mode.ANY, Set.of());
  }

  public static ConditionDomain exact(NonCuConditions value) {
    return new ConditionDomain(Mode.EXACT, Set.of(value), value.materialShapeScope());
  }

  /** A shape-specific price never occupies, or reuses, an ordinary or another-shape domain. */
  public ConditionDomain forMaterialShape(String scope) {
    return new ConditionDomain(mode, values.stream().map(value -> value.withMaterialShapeScope(scope))
        .collect(java.util.stream.Collectors.toSet()), scope);
  }

  public boolean matches(NonCuConditions conditions) {
    Objects.requireNonNull(conditions, "Request conditions are required");
    if (!Objects.equals(materialShapeScope, conditions.materialShapeScope())) return false;
    return switch (mode) {
      case ANY -> true;
      case EXACT -> values.contains(conditions);
      case EXCLUDING -> !values.contains(conditions);
    };
  }

  public Optional<ConditionDomain> intersection(ConditionDomain other) {
    Objects.requireNonNull(other, "Other condition domain is required");
    if (!Objects.equals(materialShapeScope, other.materialShapeScope)) return Optional.empty();
    if (mode == Mode.EXACT) {
      var common = new HashSet<>(values);
      if (other.mode == Mode.EXACT) common.retainAll(other.values);
      else common.removeAll(other.values);
      return finite(common, materialShapeScope);
    }
    if (other.mode == Mode.EXACT) return other.intersection(this);
    var excluded = new HashSet<>(values);
    excluded.addAll(other.values);
    return Optional.of(new ConditionDomain(Mode.EXCLUDING, excluded, materialShapeScope));
  }

  public Optional<ConditionDomain> subtract(ConditionDomain other) {
    Objects.requireNonNull(other, "Other condition domain is required");
    if (!Objects.equals(materialShapeScope, other.materialShapeScope)) return Optional.of(this);
    if (mode == Mode.EXACT) {
      var remaining = new HashSet<>(values);
      if (other.mode == Mode.EXACT) remaining.removeAll(other.values);
      else remaining.retainAll(other.values);
      return finite(remaining, materialShapeScope);
    }
    if (other.mode == Mode.EXACT) {
      var excluded = new HashSet<>(values);
      excluded.addAll(other.values);
      return Optional.of(new ConditionDomain(Mode.EXCLUDING, excluded, materialShapeScope));
    }
    var remaining = new HashSet<>(other.values);
    remaining.removeAll(values);
    return finite(remaining, materialShapeScope);
  }

  public String canonical() {
    var fields = new ArrayList<String>();
    fields.add(mode.name());
    if (materialShapeScope != null) fields.add(materialShapeScope);
    values.stream().map(ConditionDomain::conditionEncoding).sorted().forEach(fields::add);
    return MonthlyPriceCanonical.fields(materialShapeScope == null ? "CONDITION_DOMAIN_V1" : "CONDITION_DOMAIN_SHAPE_V1", fields.toArray(String[]::new));
  }

  private static Optional<ConditionDomain> finite(Set<NonCuConditions> values, String scope) {
    return values.isEmpty() ? Optional.empty() : Optional.of(new ConditionDomain(Mode.EXACT, values, scope));
  }

  private static String conditionEncoding(NonCuConditions conditions) {
    BoardTerms board = conditions.boardTerms();
    if (board == null) return MonthlyPriceCanonical.fields("NON_CU_CONDITIONS_V1", "ABSENT");
    return MonthlyPriceCanonical.fields("NON_CU_CONDITIONS_V1", "BOARD",
        MonthlyPriceCanonical.number(board.sus304PricePerTon()),
        MonthlyPriceCanonical.number(board.sus316lPricePerTon()),
        Boolean.toString(board.finalTender()));
  }
}
