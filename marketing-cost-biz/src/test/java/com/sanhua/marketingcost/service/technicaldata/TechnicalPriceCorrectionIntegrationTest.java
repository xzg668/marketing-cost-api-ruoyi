package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.PriceLinkedImportDispatchService;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalPriceCorrectionIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG =
      new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN =
      new TechnicalDataActor(1L, "报价员", Set.of("*:*:*"));
  @Autowired TechnicalDataPriceApplicationService prices;
  @Autowired TechnicalPriceCorrectionService corrections;
  @Autowired TechnicalPriceCorrectionWorkbook workbook;
  @Autowired PriceLinkedImportDispatchService imports;
  @Autowired com.sanhua.marketingcost.service.PriceLinkedItemService linkedItems;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired OaFormMapper forms;
  @Autowired OaFormItemMapper items;
  @Autowired MaterialMasterRawMapper materials;
  @Autowired com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService resolver;
  @MockBean TechnicalDataPriceRequirements requirements;
  @MockBean TechnicalDataActorProvider actors;
  @MockBean TechnicalDataOaWorkflowRepository workflow;
  private String key, material;
  private Long itemId, productId, taskId, flowId, versionId;
  private String frozen;

  @BeforeEach
  void fixture(TestInfo test) {
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "test",
            "unused",
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("*:*:*")));
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(auth);
    when(actors.current()).thenReturn(ADMIN);
    key = "TW17-" + UUID.randomUUID().toString().substring(0, 8);
    material = key + "-M";
    var form = new OaForm();
    form.setOaNo(key);
    form.setBusinessUnitType("COMMERCIAL");
    form.setProcessCode("FI-SC-006");
    forms.insert(form);
    var item = new OaFormItem();
    item.setOaFormId(form.getId());
    item.setMaterialNo(key);
    item.setBusinessUnitType("COMMERCIAL");
    items.insert(item);
    itemId = item.getId();
    jdbc.update(
        "INSERT INTO"
            + " lp_oa_technical_flow(source_system,environment,oa_form_id,accounting_month,external_document_id)"
            + " VALUES('TEST','TEST',?,'2026-09',?)",
        form.getId(),
        key);
    flowId =
        jdbc.queryForObject(
            "SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?", Long.class, form.getId());
    var task = new QuoteTechTask();
    task.setTaskNo(key);
    task.setOaNo(key);
    task.setOaFormId(form.getId());
    task.setOaFormItemId(itemId);
    task.setAccountingMonth("2026-09");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L);
    task.setAssigneeName("王工");
    task.setOaFlowId(flowId);
    taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct();
    product.setTaskId(taskId);
    product.setOaFormItemId(itemId);
    product.setQuoteNo(key);
    product.setAccountingMonth("2026-09");
    product.setMaterialNo(key);
    product.setContentSchemaVersion(2);
    product.setSourceFingerprint("a".repeat(64));
    product.setSourceSnapshotJson("{}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = type.equals("PRICE");
      var module = new QuoteTechModule();
      module.setProductId(productId);
      module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0);
      module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE");
      module.setSourceReference("TW17-CONTROLLED");
      module.setSourceCheckedAt(LocalDateTime.now());
      module.setRequirementReasonCode("TEST_CHECK");
      module.setRequirementReason("受控价格缺口");
      module.setEntryMode(required ? "MANUAL" : "NONE");
      module.setAssigneeUserId(101L);
      module.setAssigneeName("王工");
      persistence.createModule(module);
    }
    var master = new MaterialMasterRaw();
    master.setMaterialCode(material);
    master.setMaterialName("缺价部品");
    master.setUnit("只");
    master.setOrganizationCode("COMMERCIAL");
    master.setActiveFlag(1);
    master.setImportBatchId(key);
    master.setSourceType("EXCEL");
    materials.insert(master);
    when(requirements.check(any(), any()))
        .thenReturn(
            new TechnicalDataPriceRequirementsResponse(
                List.of(
                    new TechnicalDataPriceRequirement(
                        "key",
                        material,
                        "缺价部品",
                        "",
                        "210",
                        "只",
                        "CNY",
                        List.of("BOM"),
                        "MISSING",
                        null,
                        "缺价")),
                List.of(),
                "basis"));
    var request = new TechnicalDataPriceSaveRequest();
    request.setExpectedVersion(0);
    request.setRequirementsFingerprint("basis");
    var entry = new TechnicalDataPriceSaveRequest.Item();
    entry.setItemKey("key");
    entry.setEntryMode("MANUAL");
    entry.setFormula("技术原公式");
    request.setItems(List.of(entry));
    if (test.getTestMethod().orElseThrow().getName().startsWith("partial")) {
      var second = new MaterialMasterRaw();
      second.setMaterialCode(material + "2");
      second.setMaterialName("第二缺价部品");
      second.setUnit("只");
      second.setOrganizationCode("COMMERCIAL");
      second.setActiveFlag(1);
      second.setImportBatchId(key);
      second.setSourceType("EXCEL");
      materials.insert(second);
      when(requirements.check(any(), any()))
          .thenReturn(
              new TechnicalDataPriceRequirementsResponse(
                  List.of(
                      new TechnicalDataPriceRequirement(
                          "key",
                          material,
                          "缺价部品",
                          "",
                          "210",
                          "只",
                          "CNY",
                          List.of("BOM"),
                          "MISSING",
                          null,
                          "缺价"),
                      new TechnicalDataPriceRequirement(
                          "key2",
                          material + "2",
                          "第二部品",
                          "",
                          "210",
                          "只",
                          "CNY",
                          List.of("BOM"),
                          "MISSING",
                          null,
                          "缺价")),
                  List.of(),
                  "basis"));
      var next = new TechnicalDataPriceSaveRequest.Item();
      next.setItemKey("key2");
      next.setEntryMode("MANUAL");
      next.setFormula("第二技术公式");
      request.setItems(List.of(entry, next));
    }
    var saved = prices.save(productId, request, WANG);
    var person =
        new com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository
            .Recipient(
            1L,
            taskId,
            1,
            101L,
            "王工",
            "wang",
            "FILL",
            List.of("PRICE"),
            1L,
            "todo",
            "CONFIRMED",
            "OPEN",
            null,
            "工程部",
            "leader",
            "王总",
            null,
            0,
            0,
            null,
            true,
            null,
            null,
            "T-TEST", null);
    var frozenVersion =
        versions.freeze(
            repository.lockProduct(productId).orElseThrow(), person, saved.expectedVersion(), 101L);
    var approved =
        versions.transition(
            versions.transition(frozenVersion, "SUBMITTED", 101L), "APPROVED", 101L);
    versionId = approved.getId();
    versions.state(repository.lockProduct(productId).orElseThrow(), person, versionId, "APPROVED");
    versions.activate(repository.lockProduct(productId).orElseThrow(), 101L);
    jdbc.update(
        "UPDATE lp_quote_tech_task SET task_status='APPROVED',review_status='PASSED' WHERE id=?",
        taskId);
    when(workflow.findFlow(flowId))
        .thenReturn(
            new TechnicalDataOaWorkflowRepository.Flow(
                flowId,
                "TEST",
                "TEST",
                form.getId(),
                "2026-09",
                key,
                key,
                3,
                true,
                1L,
                "approved"));
    frozen = repository.findVersion(versionId).orElseThrow().getPriceItemsJson();
  }

  @AfterEach
  void cleanSecurity() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void actualImportComputesPriceReturnsIdsAndKeepsFrozenApproval() throws Exception {
    var view = corrections.get(key, itemId, "2026-09");
    assertThat(view.items()).hasSize(1);
    byte[] file = edited("12", null, null);
    var preview = imports.preview(command(file, null));
    assertThat(preview.isCanConfirm()).withFailMessage(preview.getErrors().toString()).isTrue();
    var result = imports.confirm(command(file, preview.getFileSha256()));
    assertThat(result.getTechnicalResults()).hasSize(1);
    var row = result.getTechnicalResults().getFirst();
    assertThat(row.status()).withFailMessage(row.message()).isEqualTo("AVAILABLE");
    assertThat(row.linkedItemId()).isPositive();
    assertThat(result.getFactorUploadBatchId()).isPositive();
    assertThat(corrections.get(key, itemId, "2026-09").items()).isEmpty();
    var future =
        resolver.calculateMaterialUnitPrice(
            material,
            "2027-12",
            java.time.LocalDate.of(2027, 12, 1),
            LocalDateTime.of(2027, 12, 1, 12, 0),
            key,
            "COMMERCIAL",
            null);
    assertThat(future.getStatus()).withFailMessage(future.getRemark()).isEqualTo("OK");
    assertThat(future.getUnitPrice()).isPositive();
    assertThat(repository.findVersion(versionId).orElseThrow().getPriceItemsJson())
        .isEqualTo(frozen);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_kind FROM lp_price_linked_item WHERE id=?",
                String.class,
                row.linkedItemId()))
        .isEqualTo("TECH_SUPPLEMENTAL");
    assertThat(
            jdbc.queryForObject(
                "SELECT technical_version_id FROM lp_factor_upload_batch WHERE id=?",
                Long.class,
                result.getFactorUploadBatchId()))
        .isEqualTo(versionId);
    var repeated = imports.confirm(command(file, preview.getFileSha256()));
    assertThat(repeated.getTechnicalResults().getFirst().linkedItemId())
        .isEqualTo(row.linkedItemId());
    assertThat(repeated.getTechnicalResults().getFirst().status()).isEqualTo("REUSED");
    var history = linkedItems.getImportBatchDetail(repeated.getFactorUploadBatchId());
    assertThat(history.getLinkedVersionCreatedCount()).isZero();
    assertThat(history.getLinkedUnchangedSkippedCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_price_linked_item WHERE technical_version_id=?",
                Integer.class,
                versionId))
        .isEqualTo(1);
    var changed = edited("13", null, null);
    var next =
        imports.confirm(command(changed, imports.preview(command(changed, null)).getFileSha256()));
    assertThat(next.getTechnicalResults().getFirst().linkedItemId())
        .isNotEqualTo(row.linkedItemId());
    assertThat(
            jdbc.queryForObject(
                "SELECT formula_expr FROM lp_price_linked_item WHERE id=?",
                String.class,
                row.linkedItemId()))
        .isEqualTo("12");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_price_linked_item WHERE technical_version_id=? AND"
                    + " technical_publication_status='AVAILABLE'",
                Integer.class,
                versionId))
        .isEqualTo(1);
    var invalid = edited("-1", null, null);
    var invalidResult =
        imports.confirm(command(invalid, imports.preview(command(invalid, null)).getFileSha256()));
    assertThat(invalidResult.getTechnicalResults().getFirst().status()).isEqualTo("WAIT_PRICE");
    assertThat(
            jdbc.queryForObject(
                "SELECT technical_publication_status FROM lp_price_linked_item WHERE id=?",
                String.class,
                next.getTechnicalResults().getFirst().linkedItemId()))
        .isEqualTo("AVAILABLE");
    assertThat(corrections.get(key, itemId, "2026-09").items()).isEmpty();
  }

  @Test
  void ownershipAndChangedFileRejectWholeImportWithoutWrites() throws Exception {
    byte[] file = edited("12", null, null);
    var preview = imports.preview(command(file, null));
    assertThatThrownBy(
            () -> imports.confirm(command(edited("13", null, null), preview.getFileSha256())))
        .hasMessageContaining("SHA");
    assertThatThrownBy(() -> imports.preview(command(edited("12", 6, "OTHER"), null)))
        .hasMessageContaining("归属");
    assertThatThrownBy(() -> imports.preview(command(edited("12", 8, "千克"), null)))
        .hasMessageContaining("采购单位");
    assertThatThrownBy(
            () ->
                imports.preview(
                    new PriceLinkedImportCommand(
                        file,
                        "file.xlsx",
                        "2026-09",
                        "COMMERCIAL",
                        false,
                        null,
                        "2026-09-01",
                        "KEEP_EXISTING",
                        null)))
        .hasMessageContaining("上下文");
    var stale =
        command(file, null)
            .withTechnicalContext(new TechnicalPriceImportContext(key, itemId, versionId + 100));
    assertThatThrownBy(() -> imports.preview(stale)).hasMessageContaining("版本已变化");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_price_linked_item WHERE technical_version_id=?",
                Integer.class,
                versionId))
        .isZero();
  }

  @Test
  void actorFinanceAndMonthCannotBeBypassed() throws Exception {
    byte[] file = edited("12", null, null);
    when(actors.current()).thenReturn(WANG);
    assertThatThrownBy(() -> imports.preview(command(file, null))).hasMessageContaining("无权");
    when(actors.current()).thenReturn(ADMIN);
    when(workflow.findFlow(flowId)).thenReturn(null);
    assertThatThrownBy(() -> imports.preview(command(file, null))).hasMessageContaining("财务");
    assertThatThrownBy(
            () ->
                imports.preview(
                    new PriceLinkedImportCommand(
                            file,
                            "file.xlsx",
                            "2026-10",
                            "COMMERCIAL",
                            false,
                            null,
                            "2026-10-01",
                            "KEEP_EXISTING",
                            null)
                        .withTechnicalContext(
                            new TechnicalPriceImportContext(key, itemId, versionId))))
        .hasMessageContaining("月份");
  }

  @Test
  void invalidFormulaIsVisibleAndDownloadNeverMarksPriceAvailable() throws Exception {
    var file = edited("(", null, null);
    var preview = imports.preview(command(file, null));
    assertThat(preview.isCanConfirm()).isFalse();
    assertThat(preview.getErrors()).anyMatch(row -> "key".equals(row.getItemKey()));
    assertThat(corrections.get(key, itemId, "2026-09").items()).hasSize(1);
  }

  @Test
  void partialImportAndRowReorderingRetainOnlyUnresolvedItems() throws Exception {
    var view = corrections.get(key, itemId, "2026-09");
    var scope =
        corrections.require(
            new TechnicalPriceImportContext(key, itemId, versionId),
            "2026-09",
            "COMMERCIAL",
            ADMIN,
            false);
    byte[] file;
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(scope, view)));
        var output = new ByteArrayOutputStream()) {
      var sheet = book.getSheetAt(0);
      var first = sheet.getRow(1);
      var second = sheet.getRow(2);
      // Swap the entire business rows; stable item keys, not original row numbers, determine
      // attribution.
      for (int col = 0; col < 20; col++) {
        String value = first.getCell(col).getStringCellValue();
        first.getCell(col).setCellValue(second.getCell(col).getStringCellValue());
        second.getCell(col).setCellValue(value);
      }
      first.getCell(9).setCellValue("(");
      first.getCell(15).setCellValue("0");
      second.getCell(9).setCellValue("12");
      second.getCell(15).setCellValue("0");
      book.write(output);
      file = output.toByteArray();
    }
    var preview = imports.preview(command(file, null));
    assertThat(preview.isCanConfirm()).isTrue();
    var result = imports.confirm(command(file, preview.getFileSha256()));
    assertThat(result.getImportStatus()).isEqualTo("PARTIAL");
    assertThat(result.getTechnicalResults())
        .anySatisfy(
            row -> {
              assertThat(row.itemKey()).isEqualTo("key");
              assertThat(row.rowNumber()).isEqualTo(3);
              assertThat(row.status()).isEqualTo("AVAILABLE");
            });
    assertThat(result.getTechnicalResults())
        .anySatisfy(
            row -> {
              assertThat(row.itemKey()).isEqualTo("key2");
              assertThat(row.rowNumber()).isEqualTo(2);
              assertThat(row.linkedItemId()).isNull();
              assertThat(row.status()).isEqualTo("FAILED");
            });
    assertThat(corrections.get(key, itemId, "2026-09").items())
        .extracting(TechnicalPriceCorrection.Item::itemKey)
        .containsExactly("key2");
  }

  @Test
  void publicFixedPriceResolvesReminderWithoutInventingImport() {
    jdbc.update(
        "INSERT INTO"
            + " lp_price_fixed_item(material_code,org_code,unit,business_unit_type,source_kind,source_type,fixed_price,tax_included,effective_from,created_at,updated_at)"
            + " VALUES(?,'210','只','COMMERCIAL','PUBLIC','PURCHASE_FIXED',10,0,'2026-01-01',NOW(),NOW())",
        material);
    assertThat(corrections.get(key, itemId, "2026-09").items()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_factor_upload_batch WHERE technical_version_id=?",
                Integer.class,
                versionId))
        .isZero();
    assertThat(repository.findVersion(versionId).orElseThrow().getPriceItemsJson())
        .isEqualTo(frozen);
  }

  @Test
  void type2WorkbookAlsoChecksOwnershipShaAndReturnsActualFormulaId() throws Exception {
    byte[] file;
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(edited("12", null, null)));
        var output = new ByteArrayOutputStream()) {
      var standard = book.getSheetAt(0).getRow(1);
      standard.getCell(2).setCellValue("供应商甲");
      standard.getCell(3).setCellValue("SUP-TW17");
      standard.getCell(15).setCellValue("1");
      var business = book.createSheet("业务计算");
      var header = business.createRow(0);
      String[] labels = {
        "序号", "产品名称", "U9代码", "型号规格", "单位", "供应商名称", "黄铜毛重(g)", "加工费", "现含税价", "现不含税价"
      };
      for (int col = 0; col < labels.length; col++)
        header.createCell(col).setCellValue(labels[col]);
      var row = business.createRow(1);
      row.createCell(0).setCellValue(1);
      row.createCell(1).setCellValue("部品");
      row.createCell(2).setCellValue(material);
      row.createCell(3).setCellValue("TW17");
      row.createCell(4).setCellValue("只");
      row.createCell(5).setCellValue("供应商甲");
      row.createCell(6).setCellValue(1);
      row.createCell(7).setCellValue(12);
      row.createCell(8).setCellFormula("H2*1.13");
      row.createCell(9).setCellFormula("I2/1.13");
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(output);
      file = output.toByteArray();
    }
    var preview = imports.preview(command(file, null));
    assertThat(preview.getTemplateType()).isEqualTo("TYPE2");
    assertThat(preview.isCanConfirm())
        .withFailMessage(
            preview.getErrors().stream()
                .map(PriceItemImportResponse.ErrorRow::getMessage)
                .toList()
                .toString())
        .isTrue();
    assertThatThrownBy(() -> imports.confirm(command(file, "wrong-file")))
        .hasMessageContaining("SHA");
    var result = imports.confirm(command(file, preview.getFileSha256()));
    assertThat(result.getTechnicalResults()).hasSize(1);
    var row = result.getTechnicalResults().getFirst();
    assertThat(row.linkedItemId()).isPositive();
    assertThat(row.status()).withFailMessage(row.message()).isEqualTo("AVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT source_kind FROM lp_price_linked_item WHERE id=?",
                String.class,
                row.linkedItemId()))
        .isEqualTo("TECH_SUPPLEMENTAL");
  }

  private byte[] edited(String formula, Integer column, String value) throws Exception {
    var view = corrections.get(key, itemId, "2026-09");
    // Already solved rows may be re-imported idempotently; construct the original pending export
    // basis for that replay.
    if (view.items().isEmpty())
      view =
          new TechnicalPriceCorrection(
              key,
              itemId,
              "2026-09",
              "COMMERCIAL",
              versionId,
              repository.findVersion(versionId).orElseThrow().getContentFingerprint(),
              true,
              List.of(new TechnicalPriceCorrection.Item("key", material, "WAIT", "待修正")));
    var scope =
        corrections.require(
            new TechnicalPriceImportContext(key, itemId, versionId),
            "2026-09",
            "COMMERCIAL",
            ADMIN,
            false);
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook.export(scope, view)));
        var output = new ByteArrayOutputStream()) {
      var row = book.getSheetAt(0).getRow(1);
      row.getCell(9).setCellValue(formula);
      row.getCell(15).setCellValue("0");
      if (column != null) row.getCell(column).setCellValue(value);
      book.write(output);
      return output.toByteArray();
    }
  }

  private PriceLinkedImportCommand command(byte[] file, String sha) {
    return new PriceLinkedImportCommand(
            file,
            "correction.xlsx",
            "2026-09",
            "COMMERCIAL",
            false,
            null,
            "2026-09-01",
            "KEEP_EXISTING",
            sha)
        .withTechnicalContext(new TechnicalPriceImportContext(key, itemId, versionId));
  }
}
