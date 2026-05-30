package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomCostingRowSubRef;

/** sub_ref 候选；costingRowPath 用于落库后把 costing_row_id 回填到 ref。 */
public record BomSettlementSubRefCandidate(String costingRowPath, BomCostingRowSubRef subRef) {}
