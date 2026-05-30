package com.sanhua.marketingcost.service.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;

/** U9 当前有效主制造 BOM 副产品候选；引擎据此判断是否额外补结算行。 */
public record BomSettlementByproduct(
    Long sourceByproductId,
    String parentMaterialCode,
    String byproductMaterialCode,
    String byproductMaterialName,
    String byproductMaterialSpec,
    BigDecimal outputQty,
    String unit,
    String bomPurpose,
    String versionNo,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String businessUnitType) {}
