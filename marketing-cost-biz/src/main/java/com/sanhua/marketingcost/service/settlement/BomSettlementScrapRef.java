package com.sanhua.marketingcost.service.settlement;

import java.time.LocalDate;

/** lp_material_scrap_ref 的轻量内存映射，用于副产品是否已作为废料抵减的判断。 */
public record BomSettlementScrapRef(
    String materialCode,
    String scrapCode,
    String businessUnitType,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
