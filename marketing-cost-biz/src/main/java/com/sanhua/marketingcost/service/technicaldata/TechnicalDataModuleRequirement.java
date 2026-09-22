package com.sanhua.marketingcost.service.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataModuleRequirement(
    String moduleType,
    boolean required,
    String reasonCode,
    String reason,
    TechnicalDataAvailability availability,
    String sourceReference,
    LocalDateTime checkedAt) {}
