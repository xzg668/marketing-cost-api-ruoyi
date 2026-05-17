package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import java.util.List;

public interface PriceLinkedBindingCandidateBuilder {
  BindingCandidateBuildResult build(
      String materialCode,
      String linkedItemImportKey,
      String formulaText,
      List<ResolvedFactorRef> resolvedRefs);
}
