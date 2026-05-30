package com.sanhua.marketingcost.service.settlement;

import java.time.LocalDate;
import java.util.List;

/** 读取 U9 副产品档案和废料映射，并转换为统一引擎可消费的内存候选。 */
public interface BomByproductSettlementAdapter {

  BomByproductSettlementReadResult read(
      List<BomSettlementNode> nodes,
      LocalDate asOfDate,
      String businessUnitType,
      String bomPurpose);
}
