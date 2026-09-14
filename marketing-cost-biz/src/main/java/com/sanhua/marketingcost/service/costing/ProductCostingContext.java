package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;

/** 一次产品核算的归属、月份与输入快照；所有阶段使用同一上下文。 */
public record ProductCostingContext(
    OaForm form,
    OaFormItem item,
    String productCode,
    String periodMonth,
    String initiatedBy,
    String sourceRevision) {

  public String oaNo() { return form.getOaNo(); }

  public Long itemId() { return item.getId(); }

  public ProductCostingContext withRevision(String revision) {
    return new ProductCostingContext(form, item, productCode, periodMonth, initiatedBy, revision);
  }
}
