package com.sanhua.marketingcost.dto.quotebom;

public record BomSupplementTaskQueryRequest(
    String taskNo,
    String oaNo,
    String productCode,
    String taskStatus,
    String reviewStatus,
    Integer pageNo,
    Integer pageSize) {}
