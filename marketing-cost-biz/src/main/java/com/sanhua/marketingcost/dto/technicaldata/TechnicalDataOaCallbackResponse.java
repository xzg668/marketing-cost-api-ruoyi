package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalDataOaCallbackResponse(
    String action, Long taskId, String externalTaskId, String status, Long acceptedSequence) {}
