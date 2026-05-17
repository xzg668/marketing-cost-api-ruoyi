package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * CMS Excel 行模型。
 *
 * <p>字段名尽量贴近 Excel 英文字段，转换为 SourceRow 后才进入统一导入链路。
 */
@Getter
@Setter
public class CmsMaterialScrapRefExcelRow {
  private Integer rowNo;
  private String materialCode;
  private String materialName;
  private String materialSpecifications;
  private String materialModel;
  private String materialUnit;
  private String recycleMaterialCode;
  private String recycleMaterialName;
  private String recycleMaterialSpecification;
  private String recycleMaterialModel;
  private String recycleMaterialUnit;
  private String recycleMaterialInfoVersion;
  private String costGroupName;
  private String sequenceNo;
  private String id;
  private String sequenceStatus;
  private String linkDetailId;
  private LocalDate syncTime;
  private LocalDate approvalTime;
  private String costGroupCode;
  private LocalDate effectiveDate;
  private String postingPeriod;

  public CmsMaterialScrapRefSourceRow toSourceRow() {
    CmsMaterialScrapRefSourceRow row = new CmsMaterialScrapRefSourceRow();
    row.setRowNo(rowNo);
    row.setSourceType("EXCEL");
    row.setMaterialCode(materialCode);
    row.setNormalizedMaterialCode(CmsFieldNormalizeUtils.normalize(materialCode));
    row.setMaterialName(materialName);
    row.setMaterialSpec(materialSpecifications);
    row.setNormalizedMaterialSpec(CmsFieldNormalizeUtils.normalize(materialSpecifications));
    row.setMaterialModel(materialModel);
    row.setMaterialUnit(materialUnit);
    row.setRecycleMaterialCode(recycleMaterialCode);
    row.setNormalizedRecycleMaterialCode(CmsFieldNormalizeUtils.normalize(recycleMaterialCode));
    row.setRecycleMaterialName(recycleMaterialName);
    row.setRecycleMaterialSpec(recycleMaterialSpecification);
    row.setRecycleMaterialModel(recycleMaterialModel);
    row.setRecycleMaterialUnit(recycleMaterialUnit);
    row.setCmsVersion(recycleMaterialInfoVersion);
    row.setCostGroupCode(costGroupCode);
    row.setCostGroupName(costGroupName);
    row.setSequenceNo(sequenceNo);
    row.setCmsRecordId(id);
    row.setLinkDetailId(linkDetailId);
    row.setSequenceStatus(sequenceStatus);
    row.setInvalidSequenceStatus(CmsFieldNormalizeUtils.isInvalidSequenceStatus(sequenceStatus));
    row.setCurrentMappingCandidate(
        CmsFieldNormalizeUtils.isCurrentMappingCandidate(
            materialCode, recycleMaterialCode, sequenceStatus));
    row.setSyncTime(syncTime);
    row.setApprovalTime(approvalTime);
    row.setEffectiveDate(effectiveDate);
    row.setPostingPeriod(postingPeriod);
    return row;
  }
}
