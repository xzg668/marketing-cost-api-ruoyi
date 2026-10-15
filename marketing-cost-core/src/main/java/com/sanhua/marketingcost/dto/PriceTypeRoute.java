package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import java.time.LocalDate;

/**
 * 取价路由结果 —— Router 服务给出的"该物料应该走哪个 Resolver"答案。
 *
 * <p>由 (formAttr, priceType) 二元组决定下游 PriceResolver 的桶；其余字段是审计/降级线索。
 *
 * <p>使用 record 以减少样板；该 DTO 只读，不参与 MyBatis 持久化。
 */
public record PriceTypeRoute(
    /* 物料编码（业务主键，便于日志与 trace） */
    String materialCode,
    /* 物料形态（采购/制造/委外） */
    MaterialFormAttrEnum formAttr,
    /* 价格类型（4 桶之一） */
    PriceTypeEnum priceType,
    /* 配置优先级；实际命中由取价结果单独记录 */
    Integer priority,
    /* 生效起始（含），可为 null */
    LocalDate effectiveFrom,
    /* 生效结束（含），可为 null */
    LocalDate effectiveTo,
    /* 数据来源系统（srm/oa/u9/cms/manual），用于日志追溯 */
    String sourceSystem,
    /* 路由表原始价格类型文案；FIXED 桶用它区分固定采购价和结算固定价 */
    String rawPriceType,
    String sourceKind,
    Long sourceRecordId,
    com.sanhua.marketingcost.service.MaterialPricePriorityService.Configuration priorityConfiguration) {
  public PriceTypeRoute(String materialCode, MaterialFormAttrEnum formAttr, PriceTypeEnum priceType,
      Integer priority, LocalDate effectiveFrom, LocalDate effectiveTo, String sourceSystem,
      String rawPriceType, String sourceKind, Long sourceRecordId) {
    this(materialCode, formAttr, priceType, priority, effectiveFrom, effectiveTo, sourceSystem,
        rawPriceType, sourceKind, sourceRecordId, null);
  }
  public PriceTypeRoute(String materialCode, MaterialFormAttrEnum formAttr, PriceTypeEnum priceType,
      Integer priority, LocalDate effectiveFrom, LocalDate effectiveTo, String sourceSystem, String rawPriceType) {
    this(materialCode, formAttr, priceType, priority, effectiveFrom, effectiveTo, sourceSystem, rawPriceType, "PUBLIC", null);
  }

  public boolean supplemental() { return "TECH_SUPPLEMENTAL".equals(sourceKind); }

  public String selectedSourceType() {
    if (supplemental()) return priceType.name();
    if (priceType == PriceTypeEnum.MAKE) return priceType.name();
    return priorityConfiguration == null ? priceType.name()
        : com.sanhua.marketingcost.enums.MaterialPriceSourceType.fromStored(rawPriceType).name();
  }
}
