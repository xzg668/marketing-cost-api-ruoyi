package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.CostRunFeeQuery;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.CostRunFeeSourceMapper;
import com.sanhua.marketingcost.mapper.CostRunFeeSourceMapper.Read;
import com.sanhua.marketingcost.mapper.CostRunFeeSourceMapper.Slotted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/** Batch-scoped public fee facts; no global cache, customer selection, fallback or calculation. */
@Component
public final class CostRunFeeSources {
  private static final int SQL_CHUNK = 64;
  private static final int MAX_REQUESTS = 4096;
  private final CostRunFeeSourceMapper mapper;

  public CostRunFeeSources(CostRunFeeSourceMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  public List<List<MaterialMasterRaw>> readMaterial(List<CostRunFeeQuery.Material> queries) {
    return read(queries, mapper::readMaterial, row -> copy(row, MaterialMasterRaw::new));
  }

  public List<List<MaterialMasterRaw>> readNetLossModel(List<CostRunFeeQuery.NetLossModel> queries) {
    return read(queries, mapper::readNetLossModel, row -> copy(row, MaterialMasterRaw::new));
  }

  public List<List<DepartmentFundRate>> readDepartment(List<CostRunFeeQuery.Department> queries) {
    return read(queries, mapper::readDepartment, row -> copy(row, DepartmentFundRate::new));
  }

  public List<List<ManufactureRate>> readManufacture(List<CostRunFeeQuery.Manufacture> queries) {
    return read(queries, mapper::readManufacture, row -> copy(row, ManufactureRate::new));
  }

  public List<List<ThreeExpenseRate>> readThreeExpense(List<CostRunFeeQuery.ThreeExpense> queries) {
    return read(queries, mapper::readThreeExpense, row -> copy(row, ThreeExpenseRate::new));
  }

  public List<List<OtherExpenseRate>> readOtherExpense(List<CostRunFeeQuery.OtherExpense> queries) {
    return read(queries, mapper::readOtherExpense, row -> copy(row, OtherExpenseRate::new));
  }

  public List<List<ProductProperty>> readProperty(List<CostRunFeeQuery.Property> queries) {
    return read(queries, mapper::readProperty, row -> copy(row, ProductProperty::new));
  }

  public List<List<ProductPropertyRule>> readPropertyRule(List<CostRunFeeQuery.PropertyRule> queries) {
    return read(queries, mapper::readPropertyRule, row -> copy(row, ProductPropertyRule::new));
  }

  public List<List<QualityLossRate>> readQualityLoss(List<CostRunFeeQuery.QualityLoss> queries) {
    return read(queries, mapper::readQualityLoss, row -> copy(row, QualityLossRate::new));
  }

  public List<List<ThreeExpenseDimensionMapping>> readDimension(List<CostRunFeeQuery.Dimension> queries) {
    return read(queries, mapper::readDimension, row -> copy(row, ThreeExpenseDimensionMapping::new));
  }

  /** Null configuration values still belong to the selected rule; fallback remains caller-owned. */
  public record RuleFact(Long id, BigDecimal decimalValue) {}

  public List<List<RuleFact>> readRule(List<CostRunFeeQuery.Rule> queries) {
    return read(queries, mapper::readRule, row -> new RuleFact(row.getId(), row.getDecimalValue()));
  }

  private static <Q, R extends Slotted, T> List<List<T>> read(List<Q> queries,
      Function<List<Read<Q>>, List<R>> fetch, Function<R, T> detach) {
    var stable = List.copyOf(queries);
    if (stable.size() > MAX_REQUESTS) throw new IllegalArgumentException("每批公共费率请求不能超过4096个");
    var slots = new LinkedHashMap<Q, Integer>();
    for (var query : stable) slots.computeIfAbsent(query, ignored -> slots.size());
    var distinct = slots.entrySet().stream().map(e -> new Read<>(e.getValue(), e.getKey())).toList();
    var rows = new ArrayList<List<R>>(slots.size());
    for (int i = 0; i < slots.size(); i++) rows.add(new ArrayList<>());
    for (int start = 0; start < distinct.size(); start += SQL_CHUNK) {
      for (var row : fetch.apply(distinct.subList(start, Math.min(start + SQL_CHUNK, distinct.size())))) {
        rows.get(row.getReadSlot()).add(row);
      }
    }
    // Each consumer receives detached rows, including consumers with the same public scope.
    return stable.stream().map(q -> rows.get(slots.get(q)).stream().map(detach).toList()).toList();
  }

  private static <S, T> T copy(S source, Supplier<T> factory) {
    T target = factory.get();
    BeanUtils.copyProperties(source, target);
    return target;
  }
}
