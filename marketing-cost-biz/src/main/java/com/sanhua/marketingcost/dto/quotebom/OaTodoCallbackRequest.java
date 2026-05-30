package com.sanhua.marketingcost.dto.quotebom;

public record OaTodoCallbackRequest(
    Long taskId,
    String taskNo,
    String oaTodoId,
    String status,
    String comment,
    String operatorName) {}
