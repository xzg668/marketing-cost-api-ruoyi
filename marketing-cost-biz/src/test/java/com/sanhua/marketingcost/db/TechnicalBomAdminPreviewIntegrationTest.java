package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAttachmentStore;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalBomSaveRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15747",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class TechnicalBomAdminPreviewIntegrationTest extends TechnicalCompleteBomCostingIntegrationTest {
  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired TechnicalDataAttachmentStore attachments;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort drawingContexts;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationService verification;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.mapper.OaFormItemMapper quoteItems;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper drawingTasks;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationStore drawingStore;
  @org.springframework.beans.factory.annotation.Autowired com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec drawingCodec;
  private static final List<String> PROTECTED = List.of("lp_quote_tech_task", "lp_quote_tech_product",
      "lp_quote_tech_module", "lp_quote_tech_data_version", "lp_quote_cost_run_version", "lp_bom_raw_hierarchy",
      "lp_quote_bom_preparation_record", "lp_drawing_verification_task", "lp_drawing_verification_result", "lp_electronic_drawing_source_node");

  private void receivedDrawing() throws Exception {
    String drawing = code + "-MODEL-MX";
    jdbc.update("UPDATE lp_material_master_raw SET drawing_no=? WHERE material_code=? AND organization_code='COMMERCIAL'",drawing,code);
    var scope=drawingContexts.load(item,"COMMERCIAL","210",month);
    String input=org.springframework.test.util.ReflectionTestUtils.invokeMethod(verification,"snapshot",scope,quoteItems.selectById(item),drawing);
    String key=org.springframework.test.util.ReflectionTestUtils.invokeMethod(verification,"scopeKey",scope);
    var task=new com.sanhua.marketingcost.entity.DrawingVerificationTask();task.setSourceSystem("DRAWING_PDF");task.setRequestId(UUID.randomUUID().toString());
    task.setOaNo(code);task.setOaFormItemId(item);task.setBusinessUnitType("COMMERCIAL");task.setMaterialOrgCode("COMMERCIAL");task.setPriceOrgCode("210");task.setAccountingMonth(month);
    task.setDrawingNo(drawing);task.setInputSnapshotJson(input);task.setActiveScopeKey(key);task.setTaskState("REQUESTING");task.setConsumptionState("NOT_READY");task.setRowVersion(0);
    task.setCreatedAt(java.time.LocalDateTime.now());task.setUpdatedAt(task.getCreatedAt());drawingTasks.insert(task);
    var json=new ObjectMapper();var root=json.createObjectNode();
    root.put("taskId","isolated-"+task.getId()).put("status","reviewRequired").put("differenceCount",0).put("schemaVersion",2).put("revision",1).put("deliveryAttempts",0);
    for(String field:List.of("verificationStatus","editUrl","expiresAt","confirmedAt","deliveryStatus","engine","draftEngine")) root.putNull(field);
    root.putArray("reviewReasons");root.putObject("drafts");root.putArray("plm_data").addObject().put("drawingNo",drawing).put("plmVersion","V001");
    var group=root.putObject("data").putObject("_unknown_1");group.put("company","隔离测试");var rows=group.putObject("params").putArray("items");
    for(int i=1;i<=29;i++) {var node=rows.addObject().put("no",String.valueOf(i)).put("code","PART-"+i).put("name","图库部件"+i).put("qty","1");
      node.put("mat","").put("grade","B").put("hsf","A").putNull("weight").put("note","");}
    drawingStore.append(task,com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationStore.INITIAL,drawingCodec.parse(root.toString()),null,"RECEIVED",java.time.LocalDateTime.now());
    jdbc.update("UPDATE lp_drawing_verification_task SET consumption_state='FAILED',error_code='CONSUMPTION_FAILED',error_message=? WHERE id=?","已接收明细中没有本产品图号的有效行："+drawing,task.getId());
  }

  @Test void sourcePreviewUsesCurrentReceivedResultWithoutCreatingTasksOrConsumingIt() throws Exception {
    assertThat(boms.sourceForQuote(item,month,ADMIN).source().state()).isEqualTo("MISSING");
    receivedDrawing();var before=snapshot();
    var result=boms.sourceForQuote(item,month,ADMIN);
    assertThat(result.source().state()).isEqualTo("UNCONFIRMED");assertThat(result.source().message()).contains("图库已返回资料");
    assertThat(result.editable()).isFalse();assertThat(result.bom()).isNull();assertThat(result.productId()).isNull();
    assertThat(boms.sourceForQuote(item,month,new TechnicalDataActor(12L,"报价员",Set.of("ingest:quote:cost-run:execute"))).source()).isEqualTo(result.source());
    assertThatThrownBy(()->boms.sourceForQuote(item,month,new TechnicalDataActor(13L,"技术员",Set.of("technical:data:task:edit")))).hasMessageContaining("无权");
    assertThat(snapshot()).isEqualTo(before);
  }

  private Map<String, List<Map<String,Object>>> snapshot() {
    var data = new LinkedHashMap<String, List<Map<String,Object>>>();
    for (var table : PROTECTED) data.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    return data;
  }

  @Test void adminAndTechnicianReadIdenticalReferenceWithoutChangingAnySavedRow() {
    jdbc.update("UPDATE lp_quote_tech_task SET task_status='IN_PROGRESS',assignee_user_id=11,assignee_name='技术员' WHERE id=?",task);
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=11,assignee_name='技术员',oa_edit_allowed=1 WHERE product_id=? AND module_type='DRAWING_BOM'",product);
    var before = snapshot();
    assertThat(boms.searchForQuote(item,month,sample,ADMIN)).anyMatch(row -> sample.equals(row.code()));
    var expected = boms.reference(product,sample,new TechnicalDataActor(11L,"技术员",Set.of("technical:data:task:edit")));
    assertThat(boms.referenceForQuote(item,month,sample,ADMIN)).isEqualTo(expected);
    assertThat(snapshot()).isEqualTo(before);
    assertThatThrownBy(() -> boms.searchForQuote(item,month,sample,new TechnicalDataActor(11L,"技术员",Set.of("technical:data:task:edit")))).hasMessageContaining("管理员");
  }

  @Test void unassignedQuoteWithoutTechnicalTaskCanPreviewAndInvalidReferencePreservesState() {
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,sunl_model,business_unit_type) VALUES(?,?,'未分派',?,'COMMERCIAL')",form,code+"-NEW",code+"-NEW");
    long unassigned = jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    var before = snapshot();
    assertThat(boms.searchForQuote(unassigned,month,sample,ADMIN)).isNotEmpty();
    assertThat(boms.referenceForQuote(unassigned,month,sample,ADMIN).reference().productCode()).isEqualTo(sample);
    assertThatThrownBy(() -> boms.referenceForQuote(unassigned,month,"NONEXISTENT-BOM",ADMIN)).hasMessageContaining("档案");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test void trialQuantitiesUseTheSaveValidatorAndCannotChangeStructureOrBypassSourceRevision() {
    var before=snapshot(); var preview=boms.referenceForQuote(item,month,sample,ADMIN);
    var request=new TechnicalBomSaveRequest(); request.setEntryMode("REFERENCE");
    request.setReferenceCode(sample); request.setReferenceFingerprint(preview.reference().fingerprint());
    var quantities=new LinkedHashMap<String,BigDecimal>(); preview.rows().forEach(row->quantities.put(row.hierarchy(),row.quantity()));
    String raw=preview.rows().stream().filter(row->material.equals(row.materialCode())).findFirst().orElseThrow().hierarchy();
    quantities.put(raw,new BigDecimal("6")); request.setQuantities(quantities);
    var valid=boms.checkReferenceForQuote(item,month,request,ADMIN);
    assertThat(valid.issues()).isEmpty(); assertThat(valid.rows()).anyMatch(row->row.hierarchy().equals(raw)&&row.quantity().compareTo(new BigDecimal("6"))==0);
    quantities.put(raw,new BigDecimal("-1"));
    assertThat(boms.checkReferenceForQuote(item,month,request,ADMIN).issues()).isNotEmpty();
    quantities.remove(raw);
    assertThatThrownBy(()->boms.checkReferenceForQuote(item,month,request,ADMIN)).hasMessageContaining("节点不完整");
    quantities.put(raw,BigDecimal.ZERO); request.setReferenceFingerprint("changed");
    assertThatThrownBy(()->boms.checkReferenceForQuote(item,month,request,ADMIN)).hasMessageContaining("参考BOM已变化");
    assertThat(snapshot()).isEqualTo(before);
  }

  private List<String> storedFiles() throws Exception {
    var root=(Path)org.springframework.test.util.ReflectionTestUtils.getField(attachments,"root");
    if(!Files.exists(root)) return List.of();
    try(var paths=Files.walk(root)) { return paths.filter(Files::isRegularFile).map(Path::toString).sorted().toList(); }
  }
  private byte[] invalidUpload() throws Exception {
    try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(upload("4")));
        var out=new java.io.ByteArrayOutputStream()) {
      book.getSheet(com.sanhua.marketingcost.service.technicaldata.TechnicalBomWorkbook.ENTRY).getRow(1).getCell(9).setCellValue("-4");
      book.write(out); return out.toByteArray();
    }
  }

  @Test void trialUploadUsesActualTemplateAndValidationWithoutKeepingAnAttachment() throws Exception {
    var before=snapshot(); var beforeFiles=storedFiles();
    assertThat(boms.templateForQuote(item,month,sample,ADMIN).bytes()).isNotEmpty();
    var result=boms.previewUploadForQuote(item,month,"BOM.xlsx",upload("4"),ADMIN);
    assertThat(result.issues()).isEmpty(); assertThat(result.upload()).isNotNull();
    assertThat(result.rows().getFirst().quantity()).isEqualByComparingTo("4");
    assertThat(boms.previewUploadForQuote(item,month,"BOM.xlsx",invalidUpload(),ADMIN).issues()).isNotEmpty();
    assertThat(storedFiles()).isEqualTo(beforeFiles); assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  @EnabledIfSystemProperty(named="admin.bom.preview.browser.state",matches=".+")
  void browserSession() throws Exception {
    var state = Path.of(System.getProperty("admin.bom.preview.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    if(Boolean.getBoolean("admin.bom.preview.received")) receivedDrawing();
    var before = snapshot(); var beforeFiles=storedFiles();
    String username="bom_view_"+UUID.randomUUID().toString().substring(0,8), password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'BOM预览验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json = new ObjectMapper();
    var uploadFile=Path.of(state+".xlsx");Files.write(uploadFile,upload("4"));
    var invalidFile=Path.of(state+".invalid.xlsx");Files.write(invalidFile,invalidUpload());
    var data=new LinkedHashMap<String,Object>();
    data.putAll(Map.of("apiPort",port,"item",item,"month",month,"username",username,"password",password,
        "sample",sample,"material",material,"oaNo",code,"model",code+"-MODEL"));
    data.put("uploadFile",uploadFile.toString());data.put("invalidFile",invalidFile.toString());
    if(Boolean.getBoolean("admin.bom.preview.received")) data.put("expectedSource",boms.sourceForQuote(item,month,ADMIN));
    else data.put("expected",boms.reference(product,sample,ADMIN));
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.findAndRegisterModules().writeValueAsString(data));
    var release=Path.of(state+".release"); long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(snapshot()).isEqualTo(before); assertThat(storedFiles()).isEqualTo(beforeFiles);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"protectedTables",PROTECTED)));
  }
}
