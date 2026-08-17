package com.sanhua.marketingcost.integration.drawing;

import java.math.BigDecimal;

/** 电子图库返回的一条完整父子节点；用 nodeKey/parentNodeKey 表达关系而不是前端缩进。 */
public record ElectronicBomNode(
    String nodeKey,
    String parentNodeKey,
    Integer level,
    String materialCode,
    String materialName,
    String materialSpec,
    String materialModel,
    String drawingNo,
    String materialNature,
    BigDecimal quantityPerParent,
    String unit,
    Integer sortSeq,
    Boolean active) {}
