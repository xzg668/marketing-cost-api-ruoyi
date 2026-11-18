package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;

/** 工资没有可用审批来源时允许技术补录；保留原来源证据，不将未审批工资直接用于核算。 */
final class TechnicalDataSalarySupplement {
  static final String REASON = "当前缺少可用工资（工资未审批或发布尚未核实），请技术补录并按原流程送审";

  private TechnicalDataSalarySupplement() {}

  /** 旧检查也按当前补录规则展示；不改写原检查、指纹、成本或已办理资料。 */
  static TechnicalDataSourceCheckResponse apply(TechnicalDataSourceCheckResponse check) {
    var modules = check.modules().stream().map(module -> {
      if (!"SALARY".equals(module.moduleType())
          || module.availability() != TechnicalDataAvailability.UNCONFIRMED
          || !"SALARY_SOURCE_UNCONFIRMED".equals(module.reasonCode())
          || module.checkedAt() == null) return module;
      return new TechnicalDataModuleRequirement(module.moduleType(), true, module.reasonCode(), REASON,
          TechnicalDataAvailability.MISSING, module.sourceReference(), module.checkedAt());
    }).toList();
    return new TechnicalDataSourceCheckResponse(check.oaFormItemId(), check.accountingMonth(),
        check.fingerprint(), check.checkedAt(), modules, check.taskUpdateMessage(), check.sharedModules());
  }
}
