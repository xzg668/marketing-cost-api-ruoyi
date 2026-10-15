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
