package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;

public interface QuoteIngestService {
  QuoteIngestResponse ingest(QuoteIngestRequest request);

  /** OA 适配层已校验并锁定来源绑定；仅创建正式需求，不更新已接收的单据。 */
  QuoteIngestResponse ingestFromOa(QuoteIngestRequest request);
}
