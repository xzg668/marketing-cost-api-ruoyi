package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;

public interface CostRunResultWriter {

  void writeQuoteResult(CostRunObjectResult result, OaForm form, OaFormItem item);
}
