package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import java.time.LocalDate;

/**
 * EasyData BOM 层级事实表的只读查询服务。
 *
 * <p>{@code lp_bom_raw_hierarchy} 的展开、父子层级和累计用量均由 EasyData 计算并推送；
 * 报价系统只按组织、版本日期和用途读取，不在应用内重新构建 BOM。
 */
public interface BomHierarchyQueryService {

  /** 按顶层料号查询已由 EasyData 准备好的嵌套 BOM 树。 */
  BomHierarchyTreeDto getHierarchyTree(
      String topProductCode,
      String bomPurpose,
      LocalDate asOfDate,
      String sourceType,
      String priceOrgCode);
}
