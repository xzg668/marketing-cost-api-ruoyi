package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.security.JwtUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Three returned products: one owns the prices, two keep references and resubmit through real HTTP. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15193",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class ReturnedSharedPriceBrowserSession extends TechnicalDataNativeChainIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;
  @Autowired TechnicalDataPriceApplicationService prices;
  @MockBean TechnicalDataPriceRequirements demands;

  @Test
  @EnabledIfSystemProperty(named = "shared.price.browser.state", matches = ".+")
  void browserSession() throws Exception {
    var fixture = dispatched(3, false);
    var products = fixture.taskIds().stream().map(id -> repository.findProducts(id).getFirst()).toList();
    var original = products.getFirst();
    for (var product : products) {
      jdbc.update("UPDATE lp_quote_tech_module SET required_flag=1,module_status='PENDING',source_availability='MISSING',assignee_user_id=101,assignee_name='丁云龙',oa_edit_allowed=1 WHERE product_id=? AND module_type='PRICE'", product.getId());
      jdbc.update("UPDATE lp_quote_tech_oa_recipient SET module_types_json=JSON_ARRAY('PROFILE','PRICE') WHERE task_id=? AND assignee_user_id=101 AND active_flag=1", product.getTaskId());
      save(product.getTaskId(), DING, "2.50");
    }
    doAnswer(call -> {
      QuoteTechProduct product = call.getArgument(1);
      boolean owner = product.getId().equals(original.getId());
      return new TechnicalDataPriceRequirementsResponse(List.of(new TechnicalDataPriceRequirement(
          "shared-price", "RAW-SHARED", "共享原料", "", "210", "只", "CNY", List.of("BOM"),
          owner ? "MISSING" : "SHARED", null,
          owner ? "请补价" : "已引用原产品 " + original.getMaterialNo() + " 填好的价格，无需重复补录")),
          List.of(), "shared-price-basis");
    }).when(demands).check(any(), any());
    var request = new TechnicalDataPriceSaveRequest();
    request.setExpectedVersion(repository.findProducts(original.getTaskId()).getFirst().getRowVersion());
    request.setRequirementsFingerprint("shared-price-basis");
    var input = new TechnicalDataPriceSaveRequest.Item();
    input.setItemKey("shared-price"); input.setEntryMode("FIXED"); input.setUnitPrice(new BigDecimal("2"));
    request.setItems(List.of(input));
    prices.save(original.getId(), request, DING);
    assertThat(documents.submit(fixture.formId(), request(fixture.formId(), DING), DING).status()).isEqualTo("SUCCESS");
    var history = jdbc.queryForList("SELECT id,content_snapshot_json,content_fingerprint FROM lp_quote_tech_submission WHERE task_id IN (?,?,?) ORDER BY id", fixture.taskIds().toArray());
    var json = new ObjectMapper();
    notices.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST", "TEST", Set.of("COMMERCIAL")),
        json.createObjectNode().put("requestId", "FLOW-" + fixture.formId()).put("eventType", "TECHNICAL")
            .put("reason", "请修改原产品价格后整单重提").toString());
    for (var product : products) prices.prepareSharedReference(product.getTaskId(), DING);
    for (var product : products.subList(1, 3)) {
      assertThat(jdbc.queryForObject("SELECT last_validation_code FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", String.class, product.getId())).isEqualTo("REUSED_SUBMITTED");
      assertThat(validation.validate(product.getTaskId(), DING.userId(), DING).issues()).isEmpty();
    }
    var technician = users.getById(DING.userId());
    Path state = Path.of(System.getProperty("shared.price.browser.state"));
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "formId", fixture.formId(),
        "oaNo", taskMapper.selectById(original.getTaskId()).getOaNo(), "originalMaterial", original.getMaterialNo(),
        "products", products.stream().map(p -> Map.of("productId", p.getId(), "taskId", p.getTaskId(), "materialNo", p.getMaterialNo())).toList(),
        "technicianToken", jwt.generateOaSessionToken(technician.getEmployeeNo(), technician.getNickName(), "COMMERCIAL", fixture.formId(), "TEST", 900))));
    clearInvocations(oa);
    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    for (var row : history) assertThat(jdbc.queryForMap("SELECT id,content_snapshot_json,content_fingerprint FROM lp_quote_tech_submission WHERE id=?", row.get("id"))).isEqualTo(row);
    for (var product : products.subList(1, 3)) assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(v.price_items_json,'$.items') FROM lp_quote_tech_data_version v JOIN lp_quote_tech_product p ON p.current_edit_version_id=v.id WHERE p.id=?", Integer.class, product.getId())).isZero();
    assertThat(documents.workbench(fixture.formId(), DING).lastSubmission().status()).isEqualTo("SUCCESS");
    verify(oa, times(1)).submit(any());
    Files.writeString(Path.of(state + ".db-verified"), "{\"passed\":true,\"duplicatePriceRows\":0,\"historyPreserved\":true,\"oaCalls\":1}");
  }
}
