package com.sanhua.marketingcost.service.collaboration;

public record ElectronicBomValidationIssue(
    String nodeKey,
    String bomPath,
    String code,
    String message) {}
