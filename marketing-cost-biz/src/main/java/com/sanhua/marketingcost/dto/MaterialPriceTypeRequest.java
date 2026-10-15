package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import java.util.List;

/** 财务提交完整顺序，或明确恢复默认；范围由已生成配置ID确定。 */
public record MaterialPriceTypeRequest(String expectedRevision,
    List<MaterialPriceSourceType> order, boolean restoreDefault) {}
