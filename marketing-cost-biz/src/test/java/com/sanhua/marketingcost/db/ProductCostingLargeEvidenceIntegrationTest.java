package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false"})
class ProductCostingLargeEvidenceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired ProductCostingStateService states;
  @Autowired QuoteCostingWorkspaceService workspaces;
  @Autowired PlatformTransactionManager transactions;

  @Test void bindsMultiMegabytePriceEvidenceWithoutDatabaseFilesortAndPreservesTheFingerprint() {
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("COST-SORT-TEST", "", List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    var tx = new TransactionTemplate(transactions);
    try {
      tx.executeWithoutResult(status -> {
        long oldBuffer = jdbc.queryForObject("SELECT @@session.sort_buffer_size", Long.class);
        jdbc.execute("SET SESSION sort_buffer_size=262144");
        try {
          String key = "COST-SORT-" + UUID.randomUUID().toString().substring(0, 8);
          jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", key);
          long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
          jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
          long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
          var workspace = workspaces.lockOrCreate(key, item, key, "2026-10", "COMMERCIAL");
          workspace.setCurrentPrepareNo(key);
          workspace.setBomSourceFingerprint("BOM");
          workspace.setBomRuleFingerprint("RULE");
          workspaces.update(workspace, workspace.getLockVersion());
          // Reproduce the actual 2.5 MB saved calculation row with the normal 256 KB buffer.
          String input = "{\"calculatorVersion\":\"MAKE_PART_V1\",\"savedEvidence\":\"" + "x".repeat(2_510_123) + "\"}";
          for (String code : List.of("B", "A"))
            jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,period_month,price_org_code,oa_no,oa_form_item_id,top_product_code,material_code,item_type,quantity,unit_price,amount,status,current_flag,settlement_key,input_snapshot_json,business_unit_type) VALUES(?,'2026-10','210',?,?,?, ?,'MAKE_PART',1,2,2,'READY',1,?,?,'COMMERCIAL')",
                key, key, item, key, code, key + "/" + code, input);
          String first = states.bindCurrentPriceFingerprint(key, item, "2026-10", key);
          assertThat(first).isNotBlank();
          assertThat(states.bindCurrentPriceFingerprint(key, item, "2026-10", key)).isEqualTo(first);
          assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item WHERE prepare_no=? AND LENGTH(input_snapshot_json)>2500000", Integer.class, key)).isEqualTo(2);
          assertThat(jdbc.queryForObject("SELECT @@session.sort_buffer_size", Long.class)).isEqualTo(262144);
        } finally {
          jdbc.execute("SET SESSION sort_buffer_size=" + oldBuffer);
          status.setRollbackOnly();
        }
      });
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }
}
