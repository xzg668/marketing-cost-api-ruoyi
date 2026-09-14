package com.sanhua.marketingcost.service.technicaldata;

public record TechnicalDataModuleRequirement(
    String moduleType,
    boolean required,
    String reasonCode,
    String reason) {}
