package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService.ImportedNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("电子图库 Excel 草稿真实 MySQL 覆盖保存")
class ElectronicDrawingDraftPersistenceIntegrationTest extends BomMapperTestBase {
  private static final CollaborationPrincipal TECHNICIAN = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));

  @Autowired private TechnicalBomDraftApplicationService service;
  @Autowired private QuoteCollaborationTaskRepository repository;
  @Autowired private QuoteBomPreparationRecordMapper preparationMapper;
  @Autowired private QuoteBomSupplementVersionMapper versionMapper;
  @Autowired private QuoteBomSupplementDetailMapper detailMapper;
  @Autowired private JdbcTemplate jdbc;
  @MockBean private CollaborationCurrentPrincipalProvider principalProvider;

  private final String marker = "EDXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  private Long masterId;
  private Long productTaskId;
  private Long preparationId;
  private Long versionId;

  @BeforeAll
  static void ensureSchemas() throws Exception {
    int index = 0;
    for (String resource : List.of(
        "/db/V142__quote_bom_preparation_schema.sql",
        "/db/V206__quote_bom_price_collaboration_schema.sql")) {
      String target = "/tmp/EDXP-" + (++index) + ".sql";
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(resource), target);
      ExecResult result = MYSQL.execInContainer("sh", "-c",
          "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
              + " " + MYSQL.getDatabaseName() + " < " + target);
      if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
    }
  }

  @BeforeEach
  void authentication() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentTechnician()).thenReturn(TECHNICIAN);
  }

  @AfterEach
  void clean() {
    SecurityContextHolder.clearContext();
    if (productTaskId != null) {
      jdbc.update("DELETE FROM lp_quote_collaboration_quote_link WHERE product_task_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_product_task WHERE id=?", productTaskId);
    }
    if (masterId != null) jdbc.update("DELETE FROM lp_quote_collaboration_task WHERE id=?", masterId);
    if (versionId != null) {
      jdbc.update("DELETE FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?", versionId);
      jdbc.update("DELETE FROM lp_quote_bom_supplement_version WHERE id=?", versionId);
    }
    if (preparationId != null) {
      jdbc.update("DELETE FROM lp_quote_bom_preparation_record WHERE id=?", preparationId);
    }
  }

  @Test
  void repeatedImportOverwritesOneDraftInsteadOfAppendingDuplicateRows() {
    QuoteCollaborationProductTask task = createTask();
    List<ImportedNode> first = List.of(
        root("ROOT"), new ImportedNode("ED-1", "ROOT", null, "待匹配", "铜", "D-1", "D-1",
            "PURCHASE", BigDecimal.ONE, "件", 2, "EDX1|NODE|Q=MQ"));

    var firstSaved = service.replaceFromElectronicDrawingExcel(
        task.getId(), task.getTaskVersion(), first);
    versionId = firstSaved.supplementVersionId();
    assertThat(versionMapper.selectById(versionId).getBomSource())
        .isEqualTo("ELECTRONIC_DRAWING_EXCEL");
    assertThat(details()).hasSize(2);
    assertThat(details().get(1).getMaterialCode()).startsWith("TMP-");

    List<ImportedNode> second = List.of(
        root("N1"), new ImportedNode("N2", "N1", marker + "-C", "正式子件", "S", "M", "D-1",
            "PURCHASE", new BigDecimal("2"), "件", 2, "EDX1|NODE|Q=MQ"));
    var secondSaved = service.replaceFromElectronicDrawingExcel(
        task.getId(), firstSaved.taskVersion(), second);

    assertThat(secondSaved.supplementVersionId()).isEqualTo(versionId);
    assertThat(details()).hasSize(2);
    assertThat(details().get(1).getMaterialCode()).isEqualTo(marker + "-C");
    assertThat(details().get(1).getQtyPerParent()).isEqualByComparingTo("2");
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_bom_supplement_version WHERE id=?",
        Integer.class, versionId)).isOne();
  }

  private QuoteCollaborationProductTask createTask() {
    QuoteCollaborationTask master = new QuoteCollaborationTask();
    master.setOaFormId(positive(marker + "-FORM"));
    master.setOaNo(marker + "-OA");
    master.setBusinessUnitType("COMMERCIAL");
    master.setAccountingMonth("2026-08");
    master.setMasterStatus("WAIT_TECH");
    master.setFinanceReviewerUserId(701L);
    master.setFinanceReviewerName("财务审核员");
    master = repository.saveTask(master);
    masterId = master.getId();

    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(master.getOaFormId());
    preparation.setOaFormItemId(positive(marker + "-ITEM"));
    preparation.setOaNo(master.getOaNo());
    preparation.setQuoteProductCode(marker);
    preparation.setProductType("NON_BARE");
    preparation.setNeedPackage(0);
    preparation.setCostPeriodMonth("2026-08");
    preparation.setPreparationStatus("NEED_TECH");
    preparation.setReviewStatus("NOT_SUBMITTED");
    preparation.setTechnicianUserId(TECHNICIAN.userId());
    preparation.setTechnicianName(TECHNICIAN.userName());
    preparation.setActiveFlag(1);
    assertThat(preparationMapper.insert(preparation)).isOne();
    preparationId = preparation.getId();

    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setOriginCollaborationId(masterId);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setPriceOrgCode("210");
    task.setProductCode(marker);
    task.setProductName("测试产品");
    task.setProductSpec("S");
    task.setProductModel("M");
    task.setProductForm("NORMAL");
    task.setPrimaryScope("FULL_BOM");
    task.setNeedBom(1);
    task.setNeedPackage(0);
    task.setNeedPrice(0);
    task.setTaskStatus("BOM_IN_PROGRESS");
    task.setOriginalTechnicianUserId(TECHNICIAN.userId());
    task.setOriginalTechnicianName(TECHNICIAN.userName());
    task.setCurrentAssigneeUserId(TECHNICIAN.userId());
    task.setCurrentAssigneeName(TECHNICIAN.userName());
    task.setActiveLockKey("EDXP:" + marker);
    task.setPreparationId(preparationId);
    task = repository.saveProductTask(task);
    productTaskId = task.getId();

    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(productTaskId);
    link.setCollaborationId(masterId);
    link.setOaFormId(master.getOaFormId());
    link.setOaFormItemId(preparation.getOaFormItemId());
    link.setOaNo(master.getOaNo());
    link.setProductCode(marker);
    link.setAccountingMonth("2026-08");
    link.setApplicableOrgCode("210");
    link.setLinkType("OWNER");
    link.setLinkStatus("WAIT_SOURCE");
    link.setActiveLinkKey("OA_ITEM:" + preparation.getOaFormItemId());
    repository.saveQuoteLink(link);
    return repository.findProductTaskById(productTaskId,
        new CollaborationScope("COMMERCIAL", "210")).orElseThrow();
  }

  private ImportedNode root(String nodeId) {
    return new ImportedNode(nodeId, null, marker, "测试产品", "S", "M", "D",
        "MANUFACTURE", BigDecimal.ONE, "件", 1, "EDX1|ROOT|F=ZmlsZS54bHN4");
  }

  private List<QuoteBomSupplementDetail> details() {
    return detailMapper.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, versionId)
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
  }

  private static long positive(String value) {
    long hash = value.hashCode();
    return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash) + 1L;
  }
}
