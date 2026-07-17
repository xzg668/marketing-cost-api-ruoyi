package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffResult;

/** 比较同一单产品成本版本的 OA/财务价格准备批次并计算 Cu 材料费差异。 */
public interface QuoteCuMaterialDiffService {

  QuoteCuMaterialDiffResult calculate(Long costRunVersionId);
}
