package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BomByproductCostRuleQueryService {

  List<BomByproductCostRule> listEnabledCandidates();

  Optional<BomByproductCostRule> match(
      BomRuleNodeContext byproductContext,
      String addConditionType,
      String bomPurpose,
      LocalDate asOfDate);
}
