package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

/** 报价系统交给 OA 的受限协作入口。 */
public record CollaborationPortalAccessLinkResponse(
    Long collaborationId,
    Long technicianUserId,
    String technicianName,
    List<String> modules,
    String accessUrl,
    LocalDateTime expireTime) {}
