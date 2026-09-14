package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces a revision for every upstream input that can change a quote cost. */
public interface CostInputRevisionService {

  String currentRevision(OaForm form, OaFormItem item);

  default String currentRevision(OaForm form, OaFormItem item, String pricingMonth) {
    return currentRevision(form, item);
  }

  /**
   * 批量预检仅返回资料完整产品的修订；缺少生效技术资料的产品省略，交由逐品任务记录缺口。
   * 调用方不得把省略项当作可复用成功；基础设施异常继续抛出。
   */
  default Map<Long, String> currentRevisions(OaForm form, List<OaFormItem> items) {
    Map<Long, String> revisions = new LinkedHashMap<>();
    if (items != null) {
      for (OaFormItem item : items) {
        if (item != null && item.getId() != null) {
          try {
            revisions.put(item.getId(), currentRevision(form, item));
          } catch (EffectiveTechnicalDataException missing) {
            // 单品执行会持久化 WAIT_TECH_DATA，不能阻断整单其他产品。
          }
        }
      }
    }
    return revisions;
  }

  default Map<Long, String> currentRevisions(
      OaForm form, List<OaFormItem> items, String pricingMonth) {
    Map<Long, String> revisions = new LinkedHashMap<>();
    if (items != null) {
      for (OaFormItem item : items) {
        if (item != null && item.getId() != null) {
          try {
            revisions.put(item.getId(), currentRevision(form, item, pricingMonth));
          } catch (EffectiveTechnicalDataException missing) {
            // 与无月份重载保持一致：省略缺资料产品，继续预检其余产品。
          }
        }
      }
    }
    return revisions;
  }
}
