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

  /** 查询顶层整树，或从已有有效树提取以所查自制件为根的子树；不保存结果。 */
  BomHierarchyTreeDto getHierarchyTree(
      String topProductCode,
      String bomPurpose,
      LocalDate asOfDate,
      String sourceType,
      String priceOrgCode);
}
