package com.sanhua.marketingcost.dto.financequote;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.function.IntConsumer;

/** 单个报价产品执行财务 Cu 基准核算与 OA Cu 差额回加的内部请求。 */
public record QuoteCuAdjustmentCalcRequest(
    OaForm form,
    OaFormItem item,
    String pricingMonth,
    String oaPricePrepareNo,
    String calcObjectKey,
    IntConsumer progress) {

  public QuoteCuAdjustmentCalcRequest {
    progress = progress == null ? ignored -> {} : progress;
  }
}
