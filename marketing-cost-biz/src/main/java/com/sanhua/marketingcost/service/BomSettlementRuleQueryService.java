package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BomSettlementRuleQueryService {

  List<BomSettlementRule> listEnabledCandidates();

  Optional<BomSettlementRule> match(
      BomRuleNodeContext node,
      BomRuleNodeContext parent,
      List<BomRuleNodeContext> children,
      String bomPurpose,
      LocalDate asOfDate);
}
