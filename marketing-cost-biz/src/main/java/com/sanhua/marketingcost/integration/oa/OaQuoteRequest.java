package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;

/** 已完成外部字段转换的一次性正式报价需求；documentId 为 OA 的 requestId。 */
public record OaQuoteRequest(
    String documentId, String businessUnit, QuoteIngestRequest request) {}
