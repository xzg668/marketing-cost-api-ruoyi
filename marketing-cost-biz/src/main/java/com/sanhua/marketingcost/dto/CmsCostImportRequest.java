package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsCostImportRequest {
  private String planFileName;
  private String workshopFileName;
  private String subjectFileName;
  private String subjectSettingFileName;
  private String importedBy;
  private String businessUnitType;
  private List<CmsPlanCostExcelRow> planRows = new ArrayList<>();
  private List<CmsWorkshopLaborExcelRow> workshopRows = new ArrayList<>();
  private List<CmsProductSubjectCostExcelRow> subjectRows = new ArrayList<>();
  private List<CmsSubjectSettingExcelRow> subjectSettingRows = new ArrayList<>();
}
