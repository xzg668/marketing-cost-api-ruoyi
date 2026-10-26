package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import java.util.List;

/** 沿用五行存储协议：手工首项是唯一指定类型；恢复默认则使用自动取价。 */
public record MaterialPriceTypeRequest(String expectedRevision,
    List<MaterialPriceSourceType> order, boolean restoreDefault) {}
