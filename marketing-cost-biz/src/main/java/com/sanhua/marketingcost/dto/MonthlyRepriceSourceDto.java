package com.sanhua.marketingcost.dto;

/** Saved provenance only. Does not expose the source's entire technical/quotation snapshot. */
public record MonthlyRepriceSourceDto(
    Long id, String oaNo, Long oaFormItemId, Long sourceCostVersionId, boolean selected) {}
