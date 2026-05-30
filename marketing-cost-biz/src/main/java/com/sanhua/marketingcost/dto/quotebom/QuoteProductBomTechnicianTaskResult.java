package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteProductBomTechnicianTaskResult(
    int requestedCount,
    int createdTaskCount,
    int reusedTaskCount,
    int rejectedCount,
    List<QuoteProductBomPreparationPreview> previews,
    List<String> rejectedMessages) {}
