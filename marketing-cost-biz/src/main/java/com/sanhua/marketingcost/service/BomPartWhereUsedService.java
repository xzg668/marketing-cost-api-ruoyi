package com.sanhua.marketingcost.service;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.BomPartWhereUsedItemResponse;

/** 当前有效 U9 BOM 的物料使用关系查询。 */
public interface BomPartWhereUsedService {

  PageResult<BomPartWhereUsedItemResponse> page(
      String organizationCode,
      String partCode,
      String topProductCode,
      int page,
      int pageSize);
}
