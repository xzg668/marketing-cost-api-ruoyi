package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;

/** 类型 2 联动价导入依据的版本化写入和只读查询。 */
public interface PriceLinkedImportBasisService {

  PriceLinkedImportBasisSaveResult save(PriceLinkedImportBasisSaveRequest request);

  /**
   * 查询指定公式版本的导入依据。
   *
   * @return 记录不存在或不属于当前业务单元时返回 {@code null}
   */
  PriceLinkedImportBasisResponse getImportBasis(Long linkedItemId);
}
