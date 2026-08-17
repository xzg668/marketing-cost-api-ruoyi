package com.sanhua.marketingcost.dto.collaboration;

import lombok.Getter;
import lombok.Setter;

/** 相似 BOM SQL 投影，仅由任务范围内查询使用。 */
@Getter
@Setter
public class TechnicalBomCandidateRow {
  private String productCode;
  private String productName;
  private String productSpec;
  private String productModel;
  private String bomPurpose;
  private String bomVersion;
  private Integer bomNodeCount;
  private Integer matchScore;
}
