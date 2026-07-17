package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 价格准备的内存计算结果。
 *
 * <p>第四步直接消费本对象，不需要先把临时批次、明细或缺口写入数据库。
 * 第五步则由同一计算流程在事务内持久化这些结果。
 */
@Getter
@Setter
public class PricePrepareCalculationResult {

  private PricePrepareGenerateResult summary;
  private List<PricePrepareItem> items = new ArrayList<>();
  private List<PricePrepareGap> gaps = new ArrayList<>();
}
