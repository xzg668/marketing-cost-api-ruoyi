package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;

/** 根据报价当前选择把完整有效 BOM 收敛为唯一标准/替代分支。 */
public interface QuoteAwareBomAlternativeResolver {

  BomAlternativePruneResult resolve(
      QuoteBomReadContext context, List<BomRawHierarchy> effectiveRows);
}
