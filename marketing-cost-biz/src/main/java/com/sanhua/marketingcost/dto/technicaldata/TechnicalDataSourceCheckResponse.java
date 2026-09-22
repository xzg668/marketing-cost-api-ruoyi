package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleRequirement;
import java.time.LocalDateTime;
import java.util.List;

public record TechnicalDataSourceCheckResponse(Long oaFormItemId, String accountingMonth,
    String fingerprint, LocalDateTime checkedAt, List<TechnicalDataModuleRequirement> modules,
    String taskUpdateMessage, List<TechnicalDataSharedModuleInfo> sharedModules) {}
