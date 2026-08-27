package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import java.time.LocalDate;

public interface QuoteProductBomPreparationService {

  QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId);

  QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId, LocalDate quoteDate);

}
