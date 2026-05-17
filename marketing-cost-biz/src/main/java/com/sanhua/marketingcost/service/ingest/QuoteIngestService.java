package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;

public interface QuoteIngestService {
  QuoteIngestResponse ingest(QuoteIngestRequest request);
}
