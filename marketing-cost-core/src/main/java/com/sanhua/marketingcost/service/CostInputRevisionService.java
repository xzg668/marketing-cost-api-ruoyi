package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces a revision for every upstream input that can change a quote cost. */
public interface CostInputRevisionService {

  String currentRevision(OaForm form, OaFormItem item);

  default Map<Long, String> currentRevisions(OaForm form, List<OaFormItem> items) {
    Map<Long, String> revisions = new LinkedHashMap<>();
    if (items != null) {
      for (OaFormItem item : items) {
        if (item != null && item.getId() != null) {
          revisions.put(item.getId(), currentRevision(form, item));
        }
      }
    }
    return revisions;
  }
}
