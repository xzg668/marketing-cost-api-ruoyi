package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsCostBatchPageResponse;
import com.sanhua.marketingcost.dto.CmsCostRawPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectiveLogPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectivePageResponse;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;

public interface CmsCostQueryService {

  CmsCostBatchPageResponse pageBatches(
      String batchNo, String status, int current, int size, String businessUnitType);

  CmsCostRawPageResponse<CmsPlanCostRaw> pagePlanRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      int current,
      int size,
      String businessUnitType);

  CmsCostRawPageResponse<CmsWorkshopLaborRaw> pageWorkshopRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      int current,
      int size,
      String businessUnitType);

  CmsCostRawPageResponse<CmsProductSubjectCostRaw> pageSubjectRows(
      String batchNo,
      String parentCode,
      Integer costYear,
      String period,
      String subjectCode,
      String subjectName,
      int current,
      int size,
      String businessUnitType);

  CmsCostRawPageResponse<CmsSubjectSettingRaw> pageSubjectSettings(
      String batchNo,
      String firstSubjectName,
      String secondSubjectCode,
      String secondSubjectName,
      int current,
      int size,
      String businessUnitType);

  CmsCostSourceEffectivePageResponse pageEffectiveSources(
      Integer costYear,
      String parentCode,
      String period,
      String sourceType,
      String subjectCode,
      int current,
      int size,
      String businessUnitType);

  CmsCostSourceEffectiveLogPageResponse pageEffectiveSourceLogs(
      Integer costYear,
      String parentCode,
      String period,
      String sourceType,
      String subjectCode,
      String actionType,
      int current,
      int size,
      String businessUnitType);
}
