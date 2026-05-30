package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;

/** source_ref 候选；costingRowPath 用于落库后把 costing_row_id 回填到 ref。 */
public record BomSettlementSourceRefCandidate(String costingRowPath, BomCostingRowSourceRef sourceRef) {}
