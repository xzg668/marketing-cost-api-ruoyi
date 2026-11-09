package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The URL identifies the version being edited. Null means explicitly unfilled. */
public record SupplierSupplyRatioUpdateRequest(
    @JsonProperty(required = true) BigDecimal supplyRatio,
    @JsonProperty(required = true) String selectionRule) {}
