package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationBatchResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTechnicianTaskResult;
import java.util.Collection;

public interface QuoteProductBomPreparationService {

  QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId);

  QuoteProductBomPreparationBatchResult batchPrepare(Collection<Long> itemIds);

  QuoteProductBomTechnicianTaskResult createTechnicianTask(Collection<Long> itemIds);

  QuoteProductBomPreparationPreview getPreparationPreview(Long itemId);
}
