package com.sanhua.marketingcost.integration.drawing;

import java.time.LocalDate;

public record ElectronicBomQuery(
    String productCode,
    String drawingNo,
    String materialOrganizationCode,
    String priceOrganizationCode,
    String bomPurpose,
    LocalDate asOfDate,
    String requestId) {}
