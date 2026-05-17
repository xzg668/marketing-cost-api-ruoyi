package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.CmsCostBatchPageResponse;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsCostRawPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectiveLogPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectivePageResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

class CmsCostSourceModelTest {

  @Test
  void cmsEntitiesUseExpectedTables() {
    assertThat(tableName(CmsCostImportBatch.class)).isEqualTo("cms_cost_import_batch");
    assertThat(tableName(CmsPlanCostRaw.class)).isEqualTo("cms_plan_cost_raw");
    assertThat(tableName(CmsWorkshopLaborRaw.class)).isEqualTo("cms_workshop_labor_raw");
    assertThat(tableName(CmsProductSubjectCostRaw.class)).isEqualTo("cms_product_subject_cost_raw");
    assertThat(tableName(CmsCostSourceEffective.class)).isEqualTo("cms_cost_source_effective");
    assertThat(tableName(CmsCostSourceEffectiveLog.class)).isEqualTo("cms_cost_source_effective_log");
  }

  @Test
  void cmsMappersBindToExpectedEntities() {
    assertThat(baseMapperEntity(CmsCostImportBatchMapper.class)).isEqualTo(CmsCostImportBatch.class);
    assertThat(baseMapperEntity(CmsPlanCostRawMapper.class)).isEqualTo(CmsPlanCostRaw.class);
    assertThat(baseMapperEntity(CmsWorkshopLaborRawMapper.class)).isEqualTo(CmsWorkshopLaborRaw.class);
    assertThat(baseMapperEntity(CmsProductSubjectCostRawMapper.class))
        .isEqualTo(CmsProductSubjectCostRaw.class);
    assertThat(baseMapperEntity(CmsCostSourceEffectiveMapper.class)).isEqualTo(CmsCostSourceEffective.class);
    assertThat(baseMapperEntity(CmsCostSourceEffectiveLogMapper.class)).isEqualTo(CmsCostSourceEffectiveLog.class);
  }

  @Test
  void cmsMappersAreSpringMapperComponents() {
    assertThat(CmsCostImportBatchMapper.class).hasAnnotation(Mapper.class);
    assertThat(CmsPlanCostRawMapper.class).hasAnnotation(Mapper.class);
    assertThat(CmsWorkshopLaborRawMapper.class).hasAnnotation(Mapper.class);
    assertThat(CmsProductSubjectCostRawMapper.class).hasAnnotation(Mapper.class);
    assertThat(CmsCostSourceEffectiveMapper.class).hasAnnotation(Mapper.class);
    assertThat(CmsCostSourceEffectiveLogMapper.class).hasAnnotation(Mapper.class);
  }

  @Test
  void effectiveSourceEntitiesExposeCoreFields() {
    CmsCostSourceEffective effective = new CmsCostSourceEffective();
    effective.setCostYear(2026);
    effective.setParentCode("A");
    effective.setSourceType("SALARY_DIRECT");
    effective.setPeriod("2026-01");
    effective.setSubjectCode("");
    assertThat(effective.getCostYear()).isEqualTo(2026);
    assertThat(effective.getSubjectCode()).isEmpty();

    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setCostYear(2026);
    log.setOldPeriod("2026-01");
    log.setNewPeriod("2026-04");
    log.setActionType("REFRESH");
    assertThat(log.getActionType()).isEqualTo("REFRESH");
  }

  @Test
  void dtoContractsAreConstructible() {
    CmsCostImportResponse importResponse = new CmsCostImportResponse();
    importResponse.setBatchNo("CMS-202605");
    importResponse.setPlanRowCount(2);
    assertThat(importResponse.getBatchNo()).isEqualTo("CMS-202605");
    assertThat(importResponse.getPlanRowCount()).isEqualTo(2);

    CmsCostBatchPageResponse batchPage = new CmsCostBatchPageResponse(1, List.of());
    assertThat(batchPage.getTotal()).isEqualTo(1);

    CmsCostRawPageResponse<CmsPlanCostRaw> rawPage =
        new CmsCostRawPageResponse<>("PLAN", 0, List.of());
    assertThat(rawPage.getRawType()).isEqualTo("PLAN");

    CmsCostSourceEffective effective = new CmsCostSourceEffective();
    effective.setParentCode("P-100");
    CmsCostSourceEffectivePageResponse effectivePage =
        new CmsCostSourceEffectivePageResponse(1, List.of(effective));
    assertThat(effectivePage.getTotal()).isEqualTo(1);
    assertThat(effectivePage.getRecords()).extracting(CmsCostSourceEffective::getParentCode)
        .containsExactly("P-100");

    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setActionType("REFRESH");
    CmsCostSourceEffectiveLogPageResponse logPage =
        new CmsCostSourceEffectiveLogPageResponse(1, List.of(log));
    assertThat(logPage.getRecords()).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsExactly("REFRESH");

    CmsEffectiveSourceRefreshRequest refreshRequest = new CmsEffectiveSourceRefreshRequest();
    refreshRequest.setCostYear(2026);
    refreshRequest.setParentCode("P-100");
    refreshRequest.setSourceType("AUX_SUBJECT");
    refreshRequest.setNewPeriod("2026-04");
    refreshRequest.setSubjectCode("0201");
    refreshRequest.setRefreshReason("一月 CMS 数据异常");
    assertThat(refreshRequest.getCostYear()).isEqualTo(2026);
    assertThat(refreshRequest.getNewPeriod()).isEqualTo("2026-04");
    assertThat(refreshRequest.getSubjectCode()).isEqualTo("0201");
  }

  private static String tableName(Class<?> entityClass) {
    return entityClass.getAnnotation(TableName.class).value();
  }

  private static Class<?> baseMapperEntity(Class<?> mapperClass) {
    for (var type : mapperClass.getGenericInterfaces()) {
      if (type instanceof ParameterizedType parameterizedType
          && parameterizedType.getRawType().equals(BaseMapper.class)) {
        return (Class<?>) parameterizedType.getActualTypeArguments()[0];
      }
    }
    throw new AssertionError("Mapper 未直接继承 BaseMapper: " + mapperClass.getName());
  }
}
