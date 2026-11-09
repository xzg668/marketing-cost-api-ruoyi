package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.mapper.CostRunManufacturingSourceMapper;
import com.sanhua.marketingcost.mapper.CostRunManufacturingSourceMapper.Read;
import com.sanhua.marketingcost.mapper.CostRunManufacturingSourceMapper.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/** Bounded public-source reads. Technical overrides, sums and uplift belong to the original costing. */
@Component
public final class CostRunManufacturingSources {
  public static final int MAX_BATCH_OBJECTS = 64;
  private final CostRunManufacturingSourceMapper mapper;
  private final com.sanhua.marketingcost.mapper.AuxCostItemMapper auxiliaryMapper;

  public CostRunManufacturingSources(CostRunManufacturingSourceMapper mapper) {
    this(mapper,null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public CostRunManufacturingSources(CostRunManufacturingSourceMapper mapper,
      com.sanhua.marketingcost.mapper.AuxCostItemMapper auxiliaryMapper) {
    this.mapper = Objects.requireNonNull(mapper);
    this.auxiliaryMapper=auxiliaryMapper;
  }

  public record Demand(Integer costYear, String businessUnitType, String pricingMonth,
      Set<String> materialCodes, boolean salaryNeeded, boolean auxiliaryNeeded) {
    public Demand {
      materialCodes = Collections.unmodifiableSet(new LinkedHashSet<>(materialCodes));
      if (materialCodes.size() > 256
          || materialCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
        throw new IllegalArgumentException("每个公共制造资料请求必须使用实际料号，最多256个");
      }
      businessUnitType = businessUnitType == null ? "" : businessUnitType;
    }
  }

  /** DTOs are copied per consumer. Mutating one consumer cannot alter another consumer's inputs. */
  public record Sources(List<CmsCostSourceEffective> salary, List<AuxCostItemDto> auxiliary) {
    public Sources {
      salary = List.copyOf(salary);
      auxiliary = List.copyOf(auxiliary);
    }
  }

  public List<Sources> readBatch(List<Demand> demands) {
    var stable = List.copyOf(demands);
    if (stable.size() > MAX_BATCH_OBJECTS) {
      throw new IllegalArgumentException("公共制造资料每批最多64个产品对象");
    }
    var salarySlots = new LinkedHashMap<Scope, Integer>();
    var auxiliarySlots = new LinkedHashMap<Scope, Integer>();
    for (var demand : stable) {
      if (demand.costYear() == null || demand.materialCodes().isEmpty()) continue;
      if (demand.salaryNeeded()) slot(salarySlots, scope(demand, false));
      if (demand.auxiliaryNeeded()) slot(auxiliarySlots, scope(demand, true));
    }
    List<List<CmsCostSourceEffective>> wages = emptySlots(salarySlots.size());
    List<List<AuxCostItemDto>> aux = emptySlots(auxiliarySlots.size());
    if (!salarySlots.isEmpty()) {
      for (var row : mapper.selectSalarySourcesBatch(reads(salarySlots))) {
        wages.get(row.getReadSlot()).add(row);
      }
    }
    if (!auxiliarySlots.isEmpty()) {
      for (var row : mapper.selectEffectiveAuxCostItemsBatch(reads(auxiliarySlots))) {
        aux.get(row.getReadSlot()).add(row);
      }
      auxiliarySlots.forEach((scope,slot)->SinteredBaseSurfaceCosts.apply(aux.get(slot),scope.businessUnitType(),auxiliaryMapper));
    }
    var result = new ArrayList<Sources>(stable.size());
    for (var demand : stable) {
      Integer wageSlot = demand.costYear() == null || !demand.salaryNeeded()
          ? null : salarySlots.get(scope(demand, false));
      Integer auxSlot = demand.costYear() == null || !demand.auxiliaryNeeded()
          ? null : auxiliarySlots.get(scope(demand, true));
      result.add(new Sources(
          wageSlot == null ? List.of() : wages.get(wageSlot).stream()
              .map(row -> copy(row, CmsCostSourceEffective::new)).toList(),
          auxSlot == null ? List.of() : aux.get(auxSlot).stream()
              .map(row -> copy(row, AuxCostItemDto::new)).toList()));
    }
    return List.copyOf(result);
  }

  private static Scope scope(Demand demand, boolean monthly) {
    // Annual salary intentionally ignores pricing month. Auxiliary costs retain its cutoff.
    return new Scope(demand.costYear(), demand.businessUnitType(),
        monthly ? demand.pricingMonth() : null, demand.materialCodes());
  }

  private static void slot(Map<Scope, Integer> slots, Scope scope) {
    slots.computeIfAbsent(scope, key -> slots.size());
  }

  private static <T> List<List<T>> emptySlots(int count) {
    var slots = new ArrayList<List<T>>(count);
    for (int i = 0; i < count; i++) slots.add(new ArrayList<>());
    return slots;
  }

  private static List<Read> reads(Map<Scope, Integer> slots) {
    return slots.entrySet().stream().map(entry -> new Read(entry.getValue(), entry.getKey())).toList();
  }

  private static <S, T> T copy(S source, Supplier<T> factory) {
    T target = factory.get();
    BeanUtils.copyProperties(source, target);
    return target;
  }
}
