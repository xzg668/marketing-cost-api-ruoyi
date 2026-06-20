package com.sanhua.marketingcost.dto;

import java.io.InputStream;

public record U9MaterialMasterIngestRequest(
    InputStream input,
    String sourceFileName,
    String importedBy,
    String organizationCode,
    String sourceBatchNo) {}
