package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import java.util.List;

/**
 * Supplies pre-priced BOM part rows for cost run scenes that have an approved price snapshot.
 */
public interface CostRunPreparedPartItemProvider {

  boolean supports(CostRunContext context);

  List<CostRunPartItemDto> listPreparedPartItems(CostRunContext context);
}
