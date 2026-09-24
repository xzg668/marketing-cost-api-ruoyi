package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.mapper.CmsSyncPublishSignalMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("CMS 同步发布废料映射期间保护")
class CmsSyncPublishServiceImplTest {

  private CmsSyncPublishServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new CmsSyncPublishServiceImpl(
            mock(CmsSyncPublishSignalMapper.class),
            mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),
            mock(CmsSalaryCostSourceEffectiveService.class),
            mock(CmsAuxSubjectSourceEffectiveService.class),
            360);
  }

  @Test
  @DisplayName("发布前把正式表中业务期间更新的映射补回临时表")
  void preservesNewerLiveMappingBeforeReplacement() {
    String sql = service.preserveNewerMaterialScrapSql("COMMERCIAL");

    assertThat(sql)
        .contains(
            "INSERT INTO tmp_lp_material_scrap_ref",
            "FROM lp_material_scrap_ref live",
            "live.cms_posting_period",
            "SELECT MAX(NULLIF(TRIM(candidate.cms_posting_period), ''))",
            "COALESCE(live.business_unit_type, 'COMMERCIAL') = ?")
        .containsPattern("live\\.cms_posting_period[\\s\\S]*>\\s*COALESCE");
  }

  @Test
  @DisplayName("中台暂存表与正式表排序规则不同时仍按正式表规则比对")
  void usesFormalCollationForCrossTableMatching() {
    String sql = service.preserveNewerMaterialScrapSql("COMMERCIAL").replaceAll("\\s+", " ");

    assertThat(sql)
        .contains(
            "COALESCE(incoming.business_unit_type, 'COMMERCIAL') "
                + "COLLATE utf8mb4_0900_ai_ci =",
            "incoming.material_code COLLATE utf8mb4_0900_ai_ci = live.material_code",
            "COALESCE(candidate.business_unit_type, 'COMMERCIAL') "
                + "COLLATE utf8mb4_0900_ai_ci =",
            "candidate.material_code COLLATE utf8mb4_0900_ai_ci = live.material_code",
            "COALESCE(duplicate_row.business_unit_type, 'COMMERCIAL') "
                + "COLLATE utf8mb4_0900_ai_ci =",
            "duplicate_row.material_code COLLATE utf8mb4_0900_ai_ci = live.material_code",
            "duplicate_row.scrap_code COLLATE utf8mb4_0900_ai_ci = live.scrap_code",
            "COLLATE utf8mb4_0900_ai_ci AND NOT EXISTS",
            "COALESCE(NULLIF(TRIM(live.cms_posting_period), ''), '') "
                + "COLLATE utf8mb4_0900_ai_ci");
  }

  @Test
  @DisplayName("同一原材料即使存在多个废料也只发布最新一条")
  void publishesOnlyLatestPeriodMappingGroup() {
    String sql = service.insertMaterialScrapSql("COMMERCIAL");

    assertThat(sql)
        .contains(
            "PARTITION BY COALESCE(t.business_unit_type, 'COMMERCIAL'), t.material_code",
            "t.cms_posting_period DESC",
            "t.cms_effective_date DESC",
            "t.approval_time DESC",
            "t.sync_time DESC",
            "COALESCE(ranked.business_unit_type, 'COMMERCIAL') = ?");
    assertThat(sql)
        .doesNotContain(
            "PARTITION BY COALESCE(t.business_unit_type, 'COMMERCIAL'), t.material_code, t.scrap_code");
  }
}
