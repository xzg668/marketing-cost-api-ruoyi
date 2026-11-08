package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import java.math.BigDecimal;
import java.util.*;

/** Resolved source values and customer-local presentation, separated from the original arithmetic. */
public final class ResolvedOrdinaryCost {
  private static final Set<String> CORE_CODES = Set.of("MATERIAL", "DIRECT_LABOR", "INDIRECT_LABOR",
      "LOSS", "MANUFACTURE", "MANUFACTURE_COST", "ADJUSTED_MANUFACTURE_COST", "MGMT_EXP", "SALES_EXP", "FIN_EXP", "TOTAL");
  public record Value(String code, String category, BigDecimal base, BigDecimal rate, BigDecimal amount) {}
  public record Inputs(List<Value> rows, BigDecimal independentExpenses, boolean departmentsComplete) {
    public Inputs { rows = List.copyOf(rows); Objects.requireNonNull(independentExpenses); }
  }
  /** Contains no OA, cost version or technical-source identity. */
  public record Calculated(Inputs inputs, List<Value> rows) {
    public Calculated { Objects.requireNonNull(inputs); rows = List.copyOf(rows); }
  }
  private record Origin(String code, String name, String category, String table, Long id, String remark) {}
  private final Inputs inputs;
  private final List<Origin> origins;

  public ResolvedOrdinaryCost(List<CostRunCostItemDto> rows, BigDecimal independentExpenses,
      boolean departmentsComplete) {
    for (var row : rows) if (row.getCostCode() == null || row.getCostCode().isBlank())
      throw new IllegalArgumentException("Resolved ordinary cost code required");
    inputs = new Inputs(rows.stream().map(row -> new Value(row.getCostCode(), row.getCategory(),
        row.getBaseAmount(), row.getRate(), row.getAmount())).toList(), independentExpenses, departmentsComplete);
    origins = rows.stream().map(row -> new Origin(row.getCostCode(), row.getCostName(), row.getCategory(),
        row.getSourceTable(), row.getSourceId(), row.getRemark())).toList();
  }

  public Inputs inputs() { return inputs; }

  public static Calculated calculate(Inputs input) {
    var rows = new LinkedHashMap<String, Value>();
    for (var row : input.rows()) if (CORE_CODES.contains(row.code()) && rows.putIfAbsent(row.code(), row) != null)
      throw new IllegalArgumentException("Duplicate resolved ordinary core cost code");
    var amounts = OrdinaryCostAmounts.calculate(required(rows, "MATERIAL").amount(),
        required(rows, "DIRECT_LABOR").amount(), required(rows, "INDIRECT_LABOR").amount(),
        required(rows, "LOSS").rate(), required(rows, "MANUFACTURE").rate(),
        required(rows, "ADJUSTED_MANUFACTURE_COST").rate(), required(rows, "MGMT_EXP").rate(),
        required(rows, "SALES_EXP").rate(), required(rows, "FIN_EXP").rate(), input.independentExpenses());
    replace(rows, "LOSS", amounts.lossBase(), amounts.loss());
    replace(rows, "MANUFACTURE", amounts.manufactureCost(), amounts.manufactureFee());
    replace(rows, "MANUFACTURE_COST", null, amounts.manufactureCost());
    replace(rows, "ADJUSTED_MANUFACTURE_COST", amounts.manufactureCost(), amounts.adjustedManufactureCost());
    replace(rows, "MGMT_EXP", amounts.adjustedManufactureCost(), amounts.management());
    replace(rows, "SALES_EXP", amounts.adjustedManufactureCost(), amounts.sales());
    replace(rows, "FIN_EXP", amounts.adjustedManufactureCost(), amounts.finance());
    replace(rows, "TOTAL", null, input.departmentsComplete() ? amounts.total() : null);
    // Legacy multi-material callers may carry multiple auxiliary rows with the same subject code.
    // Preserve every row and its order; only the unique derived core rows are replaced.
    return new Calculated(input, input.rows().stream().map(row -> rows.getOrDefault(row.code(), row)).toList());
  }

  /** Rebind numbers to this consumer's own sources. Never copies another consumer's detail DTOs. */
  public List<CostRunCostItemDto> bind(Calculated result) {
    if (!CalculationInputEquality.same(inputs, result.inputs()) || result.rows().size() != origins.size())
      throw new IllegalArgumentException("Shared ordinary calculation inputs differ from this consumer");
    var rows = new ArrayList<CostRunCostItemDto>(origins.size());
    for (int i = 0; i < origins.size(); i++) {
      var origin = origins.get(i); var value = result.rows().get(i);
      if (!origin.code().equals(value.code()) || !Objects.equals(origin.category(), value.category()))
        throw new IllegalArgumentException("Shared ordinary calculation detail scope differs");
      var row = new CostRunCostItemDto();
      row.setCostCode(origin.code()); row.setCostName(origin.name()); row.setCategory(origin.category());
      row.setSourceTable(origin.table()); row.setSourceId(origin.id()); row.setRemark(origin.remark());
      row.setBaseAmount(value.base()); row.setRate(value.rate()); row.setAmount(value.amount());
      rows.add(row);
    }
    return rows;
  }

  private static Value required(Map<String, Value> rows, String code) {
    return Objects.requireNonNull(rows.get(code), "Missing resolved ordinary cost: " + code);
  }
  private static void replace(Map<String, Value> rows, String code, BigDecimal base, BigDecimal amount) {
    var row = required(rows, code);
    rows.put(code, new Value(code, row.category(), base, row.rate(), amount));
  }
}
