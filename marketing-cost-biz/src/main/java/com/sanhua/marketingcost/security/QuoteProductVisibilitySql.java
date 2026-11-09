package com.sanhua.marketingcost.security;

import com.sanhua.marketingcost.enums.MaterialOrganization;

/**
 * 对齐 QuoteBomContextResolver 的组织判定，供分页前的列表/统计与资源权限共用。
 * 020专用流程优先；其他流程按产品关键字、显式料品组织及缺名时的唯一主档名称判断。
 * 纯单归各自报价员；用户确认历史混合单双方均可查看整单。
 */
final class QuoteProductVisibilitySql {
  private QuoteProductVisibilitySql() {}

  static String plateProcess(String form) {
    return "(" + process(form + ".process_code") + " OR " + process(form + ".oa_no") + ")";
  }
  private static String process(String field) {
    return "REPLACE(REPLACE(UPPER(TRIM(COALESCE(" + field
        + ",''))),'-',''),'_','') LIKE 'FISC020%'";
  }
  static String plateForm(String form) {
    return "(" + plateProcess(form) + " OR EXISTS (" + items(form) + " AND " + plateItem("qvitem") + "))";
  }
  static String commercialForm(String form) {
    return "(NOT " + plateProcess(form) + " AND (NOT EXISTS (" + items(form)
        + ") OR EXISTS (" + items(form) + " AND NOT " + plateItem("qvitem") + ")))";
  }
  private static String items(String form) {
    return "SELECT 1 FROM oa_form_item qvitem WHERE qvitem.oa_form_id=" + form + ".id AND qvitem.deleted=0";
  }
  private static String plateItem(String item) {
    // CASE保证只有OA品名缺失且有真实料号时，才补查当前有效的210/220料品名称。
    String masterName = "(SELECT MIN(TRIM(qvmm.material_name)) FROM lp_material_master_raw qvmm"
        + " WHERE qvmm.active_flag=1 AND qvmm.organization_code IN ('COMMERCIAL','PLATE')"
        + " AND qvmm.material_code=TRIM(" + item + ".material_no)"
        + " AND TRIM(COALESCE(qvmm.material_name,'')) NOT IN ('','/')"
        + " HAVING COUNT(DISTINCT CAST(TRIM(qvmm.material_name) AS BINARY))=1)";
    String name = "(CASE WHEN TRIM(COALESCE(" + item + ".product_name,'')) IN ('','/')"
        + " AND TRIM(COALESCE(" + item + ".material_no,'')) NOT IN ('','/') THEN "
        + masterName + " ELSE " + item + ".product_name END)";
    String text = "CONCAT_WS(' '," + name + "," + item + ".sunl_model," + item + ".material_no)";
    return "(UPPER(TRIM(COALESCE(" + item + ".business_unit_type,''))) IN ('PLATE','板换') OR "
        + keywords(text) + ")";
  }
  private static String keywords(String text) {
    return "REGEXP_LIKE(" + text + ",'" + String.join("|", MaterialOrganization.PLATE_PRODUCT_KEYWORDS) + "')";
  }
}
