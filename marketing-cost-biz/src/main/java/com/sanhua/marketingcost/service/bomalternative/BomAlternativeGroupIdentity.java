package com.sanhua.marketingcost.service.bomalternative;

import java.time.LocalDate;

/**
 * 替代组的稳定业务位置。
 *
 * <p>刻意不包含候选料号、数据库主键、导入/构建批次、导入时间和操作人。
 */
public record BomAlternativeGroupIdentity(
    String priceOrgCode,
    String topProductCode,
    String parentPathFingerprint,
    String parentMaterialNo,
    String bomPurpose,
    String bomVersion,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Integer childSeq,
    String processSeq) {
}
