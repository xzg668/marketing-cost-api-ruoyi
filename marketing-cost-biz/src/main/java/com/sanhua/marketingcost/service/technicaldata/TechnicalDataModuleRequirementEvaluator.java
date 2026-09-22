package com.sanhua.marketingcost.service.technicaldata;

import java.util.EnumMap;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将已核实的来源事实转为模块契约；具体来源查询由各业务检查负责。 */
@Component
public class TechnicalDataModuleRequirementEvaluator {
  public List<TechnicalDataModuleRequirement> evaluate(List<TechnicalDataSourceFact> facts) {
    if (facts == null) throw new IllegalArgumentException("来源检查不能为空");
    var byModule = new EnumMap<TechnicalDataModuleType, TechnicalDataSourceFact>(
        TechnicalDataModuleType.class);
    for (TechnicalDataSourceFact fact : facts) {
      if (fact == null || byModule.put(fact.moduleType(), fact) != null) {
        throw new IllegalArgumentException("来源检查包含空值或重复模块");
      }
    }
    return TechnicalDataModuleType.orderedCodes().stream().map(code -> {
      var type = TechnicalDataModuleType.valueOf(code);
      TechnicalDataSourceFact fact = byModule.getOrDefault(type, new TechnicalDataSourceFact(
          type, TechnicalDataAvailability.UNCONFIRMED,
          "SOURCE_NOT_CHECKED", "尚未取得本模块的来源检查结论", null, null));
      return new TechnicalDataModuleRequirement(code,
          fact.availability() == TechnicalDataAvailability.MISSING,
          fact.reasonCode(), fact.reason(), fact.availability(),
          fact.sourceReference(), fact.checkedAt());
    }).toList();
  }
}
