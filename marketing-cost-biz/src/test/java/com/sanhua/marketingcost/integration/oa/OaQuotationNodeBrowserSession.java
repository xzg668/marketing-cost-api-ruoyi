package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalReturnClient;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit browser acceptance of an actual HTTP I01 request against disposable MySQL. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15192",
    "integration.oa.mode=MOCK", "integration.oa.environment=I01_TEST",
    "integration.oa.clients.test.source-system=WEAVER", "integration.oa.clients.test.environment=I01_TEST",
    "integration.oa.clients.test.mode=MOCK", "integration.oa.clients.test.business-units=COMMERCIAL",
    "integration.oa.clients.test.secret=i01-test-only-token-at-least-32-characters",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class OaQuotationNodeBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired JwtUtils jwt;
  @Autowired OaTechnicalReturnClient returns;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL", CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("oa.node.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String key="NODE-"+UUID.randomUUID().toString().substring(0,8), child=key+"-PART";
    String requestId="FLOW-"+key, node="001327000000123456789", month=CostPricingPeriodUtils.currentPricingMonth();
    int sampleCase = Integer.getInteger("oa.node.browser.case", 2);
    var body=OaQuotationRequestMapperTest.sample(sampleCase).put("requestId",requestId).put("formNo",key).put("rejectNodeid",node);
    ((com.fasterxml.jackson.databind.node.ObjectNode)body.at("/detailData/0/fields")).put("lh",key);
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+OaQuotationIntegrationTest.URL))
        .header("Authorization",OaQuotationIntegrationTest.TOKEN).header("Content-Type","application/json")
        .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var reply=json.readTree(response.body());assertThat(reply.at("/data/status").asText()).isEqualTo("SUCCEEDED");
    long form=reply.at("/data/quoteId").asLong(), item=reply.at("/data/itemMappings/0/quoteItemId").asLong();
    for (String material:List.of(key,child)) jdbc.update("""
        INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,
          import_batch_id,active_flag,main_category_code,shape_attr)
        VALUES(?,?,'只','COMMERCIAL',?,1,?,?)
        """,material,material.equals(key)?"节点字段验收产品":"验收采购件",key,
        material.equals(key)?"10":"20",material.equals(key)?"制造件":"采购件");
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to)
        VALUES(?,'EXCEL',NOW(),'210',?,?,'主制造','V1',1,1,1,'2026-01-01','9999-12-31')
        """,key,key,child);
    for(int level:List.of(0,1)) jdbc.update("""
        INSERT INTO lp_bom_raw_hierarchy(price_org_code,top_product_code,parent_code,material_code,
          level,path,sort_seq,qty_per_parent,qty_per_top,material_name,shape_attr,source_category,
          bom_purpose,bom_version,is_leaf,effective_from,effective_to,source_type,
          source_import_batch_id,build_batch_id,built_at,business_unit_type,source_line_key)
        VALUES('210',?,?,?, ?,?,1,1,1,?,?,?,'主制造','V1',?,'2026-01-01','9999-12-31','U9',?,?,NOW(),'COMMERCIAL',?)
        """,key,key,level==0?key:child,level,"/"+key+"/"+(level==0?"":child+"/"),
        level==0?"节点字段验收产品":"验收采购件",level==0?"制造件":"采购件",level==0?"制造件":"采购件",level,key,key,UUID.randomUUID().toString());
    String username=jdbc.queryForObject("SELECT user_name FROM sys_user WHERE user_id=1",String.class);
    var value=new LinkedHashMap<String,Object>();
    value.put("apiPort",port);value.put("token",jwt.generateToken(username,"COMMERCIAL"));
    value.put("oaNo",key);value.put("formId",form);value.put("itemId",item);value.put("month",month);
    value.put("requestId",requestId);value.put("rejectNodeid",node);
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(value));
    Path release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    String stored=jdbc.queryForObject("SELECT reject_to_node_id FROM lp_oa_quote_document WHERE external_document_id=? AND source_system='WEAVER' AND environment='I01_TEST'",String.class,requestId);
    assertThat(stored).isEqualTo(node);
    var rejection=returns.previewRejection(requestId,"001001",stored);
    assertThat(rejection.path("RejectToNodeid").asText()).isEqualTo(node);
    assertThat(rejection.has("rejectNodeid")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?",Integer.class,key)).isEqualTo(1);
    int rows=jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND period_month=?",Integer.class,item,month);
    assertThat(rows).isPositive();
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of(
        "passed",true,"formId",form,"itemId",item,"month",month,"storedRejectNodeid",stored,
        "outboundPreview",rejection,"bomRows",rows,"i01HttpStatus",response.statusCode(),"i01Response",reply)));
  }
}
