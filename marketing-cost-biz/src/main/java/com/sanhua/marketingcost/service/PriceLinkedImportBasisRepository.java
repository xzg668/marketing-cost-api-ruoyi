package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import java.util.List;

/**
 * 类型 2 联动价版本及其导入依据的数据边界。
 *
 * <p>写入服务只通过该边界切换公式版本；查询服务只读取已落库快照，不重新计算公式。
 */
public interface PriceLinkedImportBasisRepository {

  PriceLinkedItem findCurrentVersion(PriceLinkedItem identity);

  PriceLinkedItem findById(Long id);

  FactorUploadBatch findUploadBatchById(Long id);

  void insertItem(PriceLinkedItem item);

  void updateItem(PriceLinkedItem item);

  void insertBinding(PriceVariableBinding binding);

  List<PriceVariableBinding> findBindings(Long linkedItemId);
}
