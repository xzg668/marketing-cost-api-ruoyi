package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

public record BomSupplementCollaborationContextResponse(
    Long tokenId,
    LocalDateTime tokenExpireTime,
    Long taskId,
    Long oaFormItemId,
    String oaNo,
    String quoteProductCode,
    String taskType,
    BomSupplementTaskDetailResponse detail) {}
