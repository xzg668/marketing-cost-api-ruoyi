package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 无 U9、无成品主档的已提交产品，通过真实 API/浏览器读取完整补录 BOM。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15192",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
@Tag("integration")
class NoU9SupplementBrowserSession extends TechnicalDataNativeChainIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;
  @Autowired SysMenuMapper menus;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @Autowired QuoteBomSupplementVersionMapper versions;
  @Autowired QuoteBomSupplementDetailMapper details;
  @Autowired ElectronicDrawingSourceNodeMapper drawingNodes;
  @Autowired ApprovedElectronicBomRawSnapshotPublisher publisher;
  @Autowired com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort drawingContexts;
  @Override protected String accountingMonth() {
    return com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("no.u9.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    var fixture = submittedFixture(1, true);
    var task = taskMapper.selectById(fixture.taskIds().getFirst());
    var product = repository.findProducts(task.getId()).getFirst();
    String oaNo = task.getOaNo(), code = product.getMaterialNo(), child = code + "-PART";
    String username = jdbc.queryForObject("SELECT user_name FROM sys_user WHERE user_id=1", String.class);
    var admin = users.getById(1L); admin.setUserName(username); admin.setPassword("unused");
    admin.setBusinessUnitType("COMMERCIAL");
    when(users.findByUsername(username)).thenReturn(admin);
    var role = new SysRole(); role.setRoleKey("admin");
    when(users.findRolesByUserId(1L)).thenReturn(List.of(role));
    when(users.findPermissionsByUserId(1L)).thenReturn(Set.of("*:*:*"));
    when(users.findVisibleMenus(1L,"COMMERCIAL")).thenReturn(menus.selectList(null));

    jdbc.update("""
        INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,
          import_batch_id,active_flag,main_category_code,shape_attr)
        VALUES(?, '无 U9 验收采购件','只','COMMERCIAL',?,1,'20','采购件')
        """, child,oaNo);
    var prep = new QuoteBomPreparationRecord();
    prep.setOaFormId(fixture.formId()); prep.setOaFormItemId(product.getOaFormItemId()); prep.setOaNo(oaNo);
    prep.setQuoteProductCode(code); prep.setCostPeriodMonth(accountingMonth()); prep.setPriceOrgCode("210");
    prep.setMaterialOrganizationCode("COMMERCIAL"); prep.setProductType("DATA_MISSING");
    prep.setPreparationStatus("ERROR"); prep.setErrorMessage("料品主档缺失，无法判断裸品/非裸品");
    prep.setBareProductCode(code); prep.setReferenceFinishedCode("OLD-REFERENCE");
    prep.setSourceTopProductCode("OLD-REFERENCE");
    prep.setActiveFlag(1); prep.setElectronicWorkflowVersion(0);
    prep.setElectronicWorkflowStage(com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowStage.COMPOSED);
    preparations.insert(prep);
    var source = new QuoteBomSupplementVersion();
    source.setPreparationId(prep.getId()); source.setOaFormItemId(product.getOaFormItemId()); source.setOaNo(oaNo);
    source.setQuoteProductCode(code); source.setProductType("NON_BARE"); source.setSupplementScope("NON_BARE_FULL_BOM");
    source.setBomSource("ELECTRONIC_DRAWING_PDF"); source.setMaterialOrgCode("COMMERCIAL");
    source.setCompositionFingerprint("a".repeat(64)); source.setVersionNo(1); source.setVersionStatus("APPROVED");
    source.setActiveFlag(1); source.setPeriodMonth(accountingMonth());
    source.setEffectiveFrom(YearMonth.parse(accountingMonth()).atDay(1));
    source.setReuseValidUntil(YearMonth.parse(accountingMonth()).plusMonths(5).atEndOfMonth());
    versions.insert(source);
    var drawingNode = new ElectronicDrawingSourceNode();
    drawingNode.setSupplementVersionId(source.getId()); drawingNode.setSourceRowNo(1);
    drawingNode.setSourceSequence("1"); drawingNode.setDrawingCode(child);
    drawingNode.setSourceName("无 U9 验收采购件"); drawingNode.setQty(BigDecimal.ONE);
    drawingNode.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AUTO);
    drawingNode.setResolvedMaterialCode(child); drawingNode.setResolvedBy("SYSTEM");
    drawingNode.setResolvedAt(LocalDateTime.now()); drawingNodes.insert(drawingNode);
    prep.setElectronicSourceVersionId(source.getId()); prep.setElectronicCompositionFingerprint(source.getCompositionFingerprint());
    preparations.updateById(prep);
    for (int level : List.of(0,1)) {
      var row = new QuoteBomSupplementDetail();
      row.setSupplementVersionId(source.getId()); row.setPreparationId(prep.getId()); row.setOaNo(oaNo);
      row.setOaFormItemId(product.getOaFormItemId()); row.setQuoteProductCode(code); row.setSupplementScope("NON_BARE_FULL_BOM");
      row.setLineNo(level+1); row.setLevel(level); row.setMaterialCode(level==0?code:child); row.setParentCode(code);
      row.setMaterialName(level==0?"无 U9 补录验收产品":"无 U9 验收采购件"); row.setShapeAttr(level==0?"制造件":"采购件");
      row.setUnit(level==0?null:"只"); row.setPath("/"+code+"/"+(level==0?"":child+"/")); row.setSortSeq(level+1);
      row.setQtyPerParent(BigDecimal.ONE); row.setQtyPerTop(BigDecimal.ONE); row.setParentBaseQty(BigDecimal.ONE);
      row.setManualFlag(1); details.insert(row);
    }
    publisher.publish(new ApprovedElectronicBomRawSnapshotPublisher.PublicationContext(
        source.getId(),code,null,"210","COMMERCIAL",accountingMonth()));
    var published = drawingContexts.completePublication(
        drawingContexts.load(product.getOaFormItemId(), "COMMERCIAL", "210", accountingMonth()),
        source.getCompositionFingerprint(), LocalDateTime.now());
    assertThat(published.published()).isTrue();
    assertThat(published.workflowStage()).isEqualTo("PUBLISHED");
    // Reproduce a later failed costing attempt after the real publication has completed.
    jdbc.update("UPDATE lp_quote_bom_preparation_record SET preparation_status='ERROR',error_message=? WHERE id=?",
        "料品主档缺失，无法判断裸品/非裸品", prep.getId());
    assertMissingRoot(code);
    var originalTechnical = jdbc.queryForList("SELECT id,content_fingerprint FROM lp_quote_tech_data_version ORDER BY id");
    var json = new ObjectMapper();
    var value = new LinkedHashMap<String,Object>();
    value.put("apiPort",port); value.put("token",jwt.generateToken(username,"COMMERCIAL"));
    value.put("oaNo",oaNo); value.put("itemId",product.getOaFormItemId()); value.put("materialNo",code);
    value.put("month",accountingMonth()); value.put("preparationId",prep.getId()); value.put("sourceId",source.getId());
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(value));
    Path release=Path.of(state+".release"); long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertMissingRoot(code);
    var result = preparations.selectById(prep.getId());
    assertThat(result.getPreparationStatus()).isEqualTo("READY");
    assertThat(result.getErrorMessage()).isNull();
    assertThat(result.getBareProductCode()).isNull();
    assertThat(result.getReferenceFinishedCode()).isNull();
    assertThat(result.getSourceTopProductCode()).isNull();
    assertThat(result.getNeedPackage()).isZero();
    assertThat(repository.findModules(product.getId()).stream().filter(m -> "DRAWING_BOM".equals(m.getModuleType()))
        .findFirst().orElseThrow().getRequiredFlag()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND period_month=?",
        Integer.class,product.getOaFormItemId(),accountingMonth())).isPositive();
    assertThat(jdbc.queryForList("SELECT id,content_fingerprint FROM lp_quote_tech_data_version ORDER BY id")).isEqualTo(originalTechnical);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,
        "itemId",product.getOaFormItemId(),"month",accountingMonth(),"preparationStatus",result.getPreparationStatus(),
        "rootMasterAbsent",true,"originalU9Absent",true,"technicalContentsPreserved",true)));
  }

  private void assertMissingRoot(String code) {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master_raw WHERE material_code=?",Integer.class,code)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master WHERE material_code=?",Integer.class,code)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND source_type='U9'",Integer.class,code)).isZero();
  }
}
