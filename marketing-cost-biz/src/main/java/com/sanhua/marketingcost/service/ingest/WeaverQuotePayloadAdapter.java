package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.Map;

/** 未来泛微 OA 原始 payload 到报价单统一接入 DTO 的适配扩展点。 */
public interface WeaverQuotePayloadAdapter {
  QuoteIngestRequest adapt(Map<String, Object> rawPayload);
}
