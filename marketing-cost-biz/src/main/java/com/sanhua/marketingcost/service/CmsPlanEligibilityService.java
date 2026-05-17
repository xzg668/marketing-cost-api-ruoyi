package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PlanEligibility;
import java.util.Collection;
import java.util.Map;

public interface CmsPlanEligibilityService {
  Map<String, PlanEligibility> checkEligibility(
      Collection<String> parentCodes, Collection<String> periods, String businessUnitType);

  Map<String, PlanEligibility> checkEligibility(
      Long importBatchId, Collection<String> parentCodes, Collection<String> periods);

  Map<String, PlanEligibility> checkDirectLaborEligibility(
      Long importBatchId, Collection<String> parentCodes);
}
