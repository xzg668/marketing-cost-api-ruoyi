package com.sanhua.marketingcost.service.quotebom;

public record ElectronicBomValidationIssue(
    String nodeKey,
    String bomPath,
    String code,
    String message) {}
