package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;

public interface QuoteCollaborationApprovedSourceInspector {

  ApprovedSourceInspection inspect(
      QuoteCollaborationApprovedResult approvedResult,
      QuoteCollaborationScanContext context,
      CurrentU9BomResult currentU9Bom);
}
