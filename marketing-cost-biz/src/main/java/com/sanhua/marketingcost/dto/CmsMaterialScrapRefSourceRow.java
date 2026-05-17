package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * CMS 原材料对应回收废料统一源行。
 *
 * <p>Excel 导入和未来 CMS API 都先转换成这个模型；T3 再基于它写入 raw 表和当前映射表。
 */
@Getter
@Setter
public class CmsMaterialScrapRefSourceRow {
  private Integer rowNo;
  private String sourceType;

  private String materialCode;
  private String normalizedMaterialCode;
  private String materialName;
  private String materialSpec;
  private String normalizedMaterialSpec;
  private String materialModel;
  private String materialUnit;

  private String recycleMaterialCode;
  private String normalizedRecycleMaterialCode;
  private String recycleMaterialName;
  private String recycleMaterialSpec;
  private String recycleMaterialModel;
  private String recycleMaterialUnit;

  private String cmsVersion;
  private String costGroupCode;
  private String costGroupName;
  private String sequenceNo;
  private String cmsRecordId;
  private String linkDetailId;
  private String sequenceStatus;
  private boolean invalidSequenceStatus;
  private boolean currentMappingCandidate;
  private LocalDate syncTime;
  private LocalDate approvalTime;
  private LocalDate effectiveDate;
  private String postingPeriod;
}
