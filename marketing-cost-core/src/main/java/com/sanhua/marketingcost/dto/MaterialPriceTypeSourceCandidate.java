package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 价格类型表缺少历史记录时，从正式价格源推断出的当前类型候选。
 *
 * <p>仅用于兼容价格类型自动同步上线前已经导入的正式价格，不复制价格值。
 */
@Getter
@Setter
public class MaterialPriceTypeSourceCandidate {

  private String materialCode;
  private String priceType;
  private String businessUnitType;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String sourceSystem;
  private LocalDateTime sourceTime;
  private Long sourceId;
}
