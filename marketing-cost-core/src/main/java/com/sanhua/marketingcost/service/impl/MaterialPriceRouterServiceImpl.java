package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.TechnicalPriceSourceMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialPriceRouterServiceImpl implements MaterialPriceRouterService {
  private final MaterialPricePriorityService priorities;
  private final MaterialMasterMapper masters;
  private final TechnicalPriceSourceMapper sources;
  private final MaterialPriceMonthSelector months;

  public MaterialPriceRouterServiceImpl(MaterialPricePriorityService priorities,
      MaterialMasterMapper masters, TechnicalPriceSourceMapper sources, MaterialPriceMonthSelector months) {
    this.priorities = priorities;
    this.masters = masters;
    this.sources = sources;
    this.months = months;
  }

  @Override
  public Map<String, List<PriceTypeRoute>> listCandidatesBatch(Collection<String> codes,
      String month, LocalDate date, String org, String businessUnit) {
    var names = new java.util.TreeMap<String, String>();
    for (String code : codes) if (code != null && !code.isBlank()) names.put(code.trim(), null);
    return candidatesBatch(names, month, date, org, businessUnit, false);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Map<String, List<PriceTypeRoute>> prepareCandidatesBatch(Map<String, String> materialNames,
      String month, LocalDate date, String org, String businessUnit) {
    var names = new java.util.TreeMap<String, String>();
    materialNames.forEach((code, name) -> {
      var scope = new Scope(businessUnit, month, org, code);
      names.put(scope.materialCode(), name);
    });
    return candidatesBatch(names, month, date, org, businessUnit, true);
  }

  private Map<String, List<PriceTypeRoute>> candidatesBatch(Map<String, String> names,
      String month, LocalDate date, String org, String businessUnit, boolean initialize) {
    if (names.isEmpty()) return Map.of();
    if (date == null) throw new IllegalArgumentException("取价日期不能为空");
    var scope = new Scope(businessUnit, month, org, names.keySet().iterator().next());
    var selected = months.select(scope.businessUnitType(), scope.priceOrgCode(), scope.period());
    var codes = List.copyOf(names.keySet());
    var result = new java.util.LinkedHashMap<String, List<PriceTypeRoute>>();
    // 只复用本次读取，跨事务、下一次核算和发布前校验均重新取数。
    for (int offset = 0; offset < codes.size(); offset += 200) {
      var batch = codes.subList(offset, Math.min(offset + 200, codes.size()));
      var batchNames = new java.util.LinkedHashMap<String, String>();
      batch.forEach(code -> batchNames.put(code, names.get(code)));
      var configurations = priorities.readBatch(scope, batchNames, initialize);
      var forms = new java.util.HashMap<String, MaterialFormAttrEnum>();
      for (var master : masters.selectList(Wrappers.lambdaQuery(MaterialMaster.class)
          .in(MaterialMaster::getMaterialCode, batch)
          .eq(MaterialMaster::getBusinessUnitType, scope.businessUnitType()))) {
        forms.put(master.getMaterialCode(), MaterialFormAttrEnum.fromDbText(master.getShapeAttr()).orElse(null));
      }
      var available = new java.util.HashMap<String, Set<String>>();
      for (var row : sources.publicKindsBatch(batch, scope.period(),
          selected == null ? null : selected.sourceMonth(), scope.businessUnitType(), scope.priceOrgCode(), date)) {
        available.computeIfAbsent(row.materialCode(), ignored -> new HashSet<>()).add(row.kind());
      }
      for (String code : batch) result.put(code,
          candidates(configurations.get(code), forms.get(code), available.getOrDefault(code, Set.of())));
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceTypeRoute> prepareCandidates(String code, String materialName, String month,
      LocalDate date, String org, String businessUnit) {
    var scope = new Scope(businessUnit, month, org, code);
    if (date == null) throw new IllegalArgumentException("取价日期不能为空");
    var configuration = priorities.initialize(scope, materialName);
    return candidates(configuration, date);
  }

  @Override
  public List<PriceTypeRoute> listCandidates(String code, String month, LocalDate date,
      String org, String businessUnit) {
    if (code == null || code.isBlank()) return List.of();
    var scope = new Scope(businessUnit, month, org, code);
    if (date == null) throw new IllegalArgumentException("取价日期不能为空");
    var configuration = priorities.read(scope);
    return candidates(configuration, date);
  }

  private List<PriceTypeRoute> candidates(MaterialPricePriorityService.Configuration configuration, LocalDate date) {
    var scope = configuration.scope();
    var master = masters.selectOne(Wrappers.lambdaQuery(MaterialMaster.class)
        .eq(MaterialMaster::getMaterialCode, scope.materialCode())
        .eq(MaterialMaster::getBusinessUnitType, scope.businessUnitType()).last("LIMIT 1"));
    var form = MaterialFormAttrEnum.fromDbText(master == null ? null : master.getShapeAttr()).orElse(null);
    var selectedMonth = months.select(scope.businessUnitType(), scope.priceOrgCode(), scope.period());
    var available = new HashSet<>(sources.publicKinds(scope.materialCode(), scope.period(),
        selectedMonth == null ? null : selectedMonth.sourceMonth(), scope.businessUnitType(), scope.priceOrgCode(), date));
    return candidates(configuration, form, available);
  }

  private List<PriceTypeRoute> candidates(MaterialPricePriorityService.Configuration configuration,
      MaterialFormAttrEnum form, Set<String> available) {
    var scope = configuration.scope();
    var result = new ArrayList<PriceTypeRoute>();
    for (var entry : configuration.order()) {
      var type = entry.type();
      // 结算联动价保留独立顺序；正式数据源接入前不可套用普通联动价或区间价。
      if (type.calculator() == null || !available.contains(type.name())) continue;
      result.add(new PriceTypeRoute(scope.materialCode(), form, type.calculator(), entry.priority(),
          null, null, configuration.manual() ? "FINANCE" : "SYSTEM", type.label(),
          "PUBLIC", null, configuration));
    }
    if (result.isEmpty()) {
      var supplemental = sources.supplemental(scope.materialCode(), scope.businessUnitType());
      if (supplemental.size() > 1) throw new IllegalStateException(scope.materialCode() + " 存在重复可用补录来源，请核实原办理记录");
      for (var source : supplemental) {
        var type = PriceTypeEnum.valueOf(source.priceType());
        result.add(new PriceTypeRoute(scope.materialCode(), form, type, 6, null, null,
            "TECH_SUPPLEMENTAL", type.getDbText(), "TECH_SUPPLEMENTAL", source.recordId(), configuration));
      }
      if (result.isEmpty() && form == MaterialFormAttrEnum.MANUFACTURED) {
        result.add(new PriceTypeRoute(scope.materialCode(), form, PriceTypeEnum.MAKE, 6, null,
            null, "BOM", PriceTypeEnum.MAKE.getDbText(), "PUBLIC", null, configuration));
      }
    } else {
      // 公共价确实不存在时才查询已发布技术补录；公式错误和财务缺价不允许这样降级。
      for (var type : List.of(PriceTypeEnum.FIXED, PriceTypeEnum.LINKED)) {
        result.add(new PriceTypeRoute(scope.materialCode(), form, type, result.size() + 6,
            null, null, "TECH_SUPPLEMENTAL", type.getDbText(), "TECH_SUPPLEMENTAL", null, configuration));
      }
    }
    return List.copyOf(result);
  }
}
