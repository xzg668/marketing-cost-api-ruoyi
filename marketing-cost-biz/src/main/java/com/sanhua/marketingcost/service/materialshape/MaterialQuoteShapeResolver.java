package com.sanhua.marketingcost.service.materialshape;

import java.util.List;
import java.util.Map;

/** 报价 BOM 节点形态解析契约；只读规则，不写任何 BOM 或历史结果。 */
public interface MaterialQuoteShapeResolver {

  MaterialQuoteShapeResolution resolve(MaterialQuoteShapeRequest request);

  /** 同一棵报价 BOM 的形态批量解析；实现必须避免按节点逐条查询规则表。 */
  Map<String, MaterialQuoteShapeResolution> resolveAll(
      List<MaterialQuoteShapeRequest> requests);
}
