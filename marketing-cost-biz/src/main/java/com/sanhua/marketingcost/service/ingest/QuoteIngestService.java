package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.entity.OaForm;
import java.util.Set;

public interface QuoteIngestService {
  QuoteIngestResponse ingest(QuoteIngestRequest request);

  /** OA 适配层已校验并锁定原流程。 */
  QuoteIngestResponse ingestFromOa(QuoteIngestRequest request);

  /** OA 适配层已锁定原报价单，按原明细行身份更新正式需求。 */
  QuoteIngestResponse updateFromOa(QuoteIngestRequest request, OaForm form,
      boolean headerChanged, Set<String> changedLines);
}
