package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import java.util.List;

public interface PriceLinkedFormulaFactorRefResolver {
  List<ResolvedFactorRef> resolve(Long factorUploadBatchId, List<FormulaFactorRef> refs);
}
