package com.sanhua.marketingcost.bom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/** 沿层级行的原始批次及完整业务身份读取底数，不能只按包装料号跨版本取值。 */
public final class U9BomBaseQuantity {
  private U9BomBaseQuantity() {}

  public static BigDecimal read(BomU9SourceMapper mapper, BomRawHierarchy row) {
    if (row == null || !"U9".equals(row.getSourceType())
        || !StringUtils.hasText(row.getSourceImportBatchId())) return null;
    var key = U9BomLineKey.from(row);
    var matches = mapper.selectList(Wrappers.<BomU9Source>lambdaQuery()
        .eq(BomU9Source::getImportBatchId, row.getSourceImportBatchId())
        .eq(BomU9Source::getPriceOrgCode, key.priceOrgCode())
        .eq(BomU9Source::getParentMaterialNo, key.parentMaterialNo())
        .eq(BomU9Source::getChildMaterialNo, key.childMaterialNo())
        .eq(BomU9Source::getBomPurpose, key.bomPurpose())
        .eq(BomU9Source::getChildSeq, key.childSeq())
        .eq(BomU9Source::getBomVersion, key.bomVersion())
        .eq(BomU9Source::getEffectiveFrom, key.effectiveFrom())
        .eq(BomU9Source::getEffectiveTo, key.effectiveTo()));
    if (matches == null || matches.isEmpty()) return null;
    if (matches.size() != 1) throw new IllegalStateException("U9 单层 BOM 业务行不唯一：" + key);
    return matches.getFirst().getParentBaseQty();
  }
}
