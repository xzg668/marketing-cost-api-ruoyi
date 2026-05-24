package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.OaProductPropertyUsageSyncRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.List;

public interface ProductPropertyAnnualUsageService {
  ProductPropertyAnnualSyncResult syncFromOaForm(OaForm form, List<OaFormItem> items);

  ProductPropertyAnnualSyncResult syncFromRequest(OaProductPropertyUsageSyncRequest request);
}
