package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.math.BigDecimal;
import java.util.Map;

/** Quotation-local shape inputs consumed by shared revision and CMS cost calculations. */
public interface QuotationMaterialShapeInputs {
  String revision(OaFormItem item,String month);
  Map<String,BigDecimal> cmsExclusions(CostRunContext context);
}
