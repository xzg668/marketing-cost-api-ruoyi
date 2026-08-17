package com.sanhua.marketingcost.dto.collaboration;

/** 复制 U9 参考 BOM 或全新建树。 */
public record TechnicalBomReferenceRequest(
    Integer expectedTaskVersion,
    String referenceProductCode,
    String bomPurpose,
    String rootMaterialNature,
    String rootMaterialName,
    String rootMaterialSpec,
    String rootMaterialModel,
    String rootDrawingNo) {}
