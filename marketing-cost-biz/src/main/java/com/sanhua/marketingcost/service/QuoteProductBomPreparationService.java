package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationBatchResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTechnicianTaskResult;
import java.time.LocalDate;
import java.util.Collection;

public interface QuoteProductBomPreparationService {

  QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId);

  QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId, LocalDate quoteDate);

  QuoteProductBomPreparationBatchResult batchPrepare(Collection<Long> itemIds);

  QuoteProductBomTechnicianTaskResult createTechnicianTask(Collection<Long> itemIds);

  QuoteProductBomPreparationPreview getPreparationPreview(Long itemId);
}
