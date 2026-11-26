package com.sanhua.marketingcost.service.quotebom;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

public final class AdoptedModelProduct {
  private AdoptedModelProduct() {}

  public static String find(JdbcTemplate jdbc, Long itemId, String month, String org, String model) {
    var codes = jdbc.queryForList("""
        SELECT material_code FROM lp_quote_model_bom_choice
        WHERE oa_form_item_id=? AND period_month=? AND material_org=? AND quote_model=?
        """, String.class, itemId, month, org, model);
    return codes.isEmpty() ? null : codes.getFirst();
  }

  public static OaFormItem forRevision(JdbcTemplate jdbc, OaForm form, OaFormItem item, String month) {
    if (StringUtils.hasText(item.getMaterialNo()) || !StringUtils.hasText(item.getSunlModel())) return item;
    var org = MaterialOrganization.quoteDataForQuoteProduct(form.getProcessCode(), form.getOaNo(),
        item.getBusinessUnitType(), item.getProductName(), item.getSunlModel(), null);
    return withCode(item, find(jdbc, item.getId(), month, org.materialOrganizationCode(), item.getSunlModel().trim()));
  }

  public static OaFormItem withCode(OaFormItem item, String materialCode) {
    if (materialCode == null) return item;
    var resolved = new OaFormItem();
    BeanUtils.copyProperties(item, resolved);
    resolved.setMaterialNo(materialCode);
    return resolved;
  }
}
