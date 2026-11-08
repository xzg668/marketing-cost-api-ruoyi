package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.util.Objects;
import org.springframework.beans.BeanUtils;

/** The selected whole quotation input, detached from subsequent changes to live OA rows. */
public record CostRunQuotationInput(OaForm form, OaFormItem item) {
  public CostRunQuotationInput {
    Objects.requireNonNull(form, "缺少选定的OA表头");
    Objects.requireNonNull(item, "缺少选定的OA产品行");
    if (form.getId() == null || item.getId() == null || !form.getId().equals(item.getOaFormId())) {
      throw new IllegalArgumentException("选定的OA产品行不属于表头");
    }
    form = copyForm(form);
    item = copyItem(item);
  }

  @Override public OaForm form() { return copyForm(form); }
  @Override public OaFormItem item() { return copyItem(item); }

  public void requireFor(CostRunContext context, String oaNo, String productCode) {
    String unit = text(item.getBusinessUnitType());
    if (unit == null) unit = text(form.getBusinessUnitType());
    if (context == null || text(oaNo) == null || text(productCode) == null
        || !Objects.equals(text(form.getOaNo()), text(oaNo))
        || !Objects.equals(text(context.getOaNo()), text(oaNo))
        || !Objects.equals(item.getId(), context.getOaFormItemId())
        || !Objects.equals(QuoteProductIdentityUtils.resolveCostingCode(item), text(productCode))
        || !Objects.equals(text(context.getProductCode()), text(productCode))
        || unit == null || !unit.equals(text(context.getBusinessUnitType()))) {
      throw new IllegalArgumentException("选定的OA资料与本次核算产品或业务单元不一致");
    }
  }

  private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static OaForm copyForm(OaForm source) {
    var target = new OaForm(); BeanUtils.copyProperties(source, target); return target;
  }
  private static OaFormItem copyItem(OaFormItem source) {
    var target = new OaFormItem(); BeanUtils.copyProperties(source, target); return target;
  }
}
