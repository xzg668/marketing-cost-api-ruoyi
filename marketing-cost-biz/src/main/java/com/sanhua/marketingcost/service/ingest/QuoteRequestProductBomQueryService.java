package com.sanhua.marketingcost.service.ingest;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import java.util.List;

public interface QuoteRequestProductBomQueryService {
  PageResult<QuoteRequestProductBomListItemResponse> pageProductBomRows(
      Integer pageNo,
      Integer pageSize,
      String oaNo,
      String productCode,
      String customer,
      String productType,
      String packageMethod,
      String businessUnit,
      String technicianName,
      Boolean needTechnicianTask,
      String reviewStatus,
      List<String> bomStatuses);
}
