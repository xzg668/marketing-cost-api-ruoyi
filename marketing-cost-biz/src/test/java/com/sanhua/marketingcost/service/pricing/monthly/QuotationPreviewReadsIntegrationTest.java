package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.pricing.QuotationPreviewReads;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class QuotationPreviewReadsIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @Test void independentReadonlyTransactionsShareOneReadAndRefreshSeesNewCommittedData() {
    String oa="PREVIEW-"+UUID.randomUUID();var calls=new AtomicInteger();
    var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var reads=new TechnicalDataPendingSourceReads();
    try {
      for(int expected=0;expected<2;expected++) {
        var values=reads.map(java.util.stream.IntStream.range(0,24).boxed().toList(),i->tx.execute(status->
            QuotationPreviewReads.read(this,List.of("oa",oa),()->{
              calls.incrementAndGet();return jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?",Integer.class,oa);
            })));
        assertThat(values).hasSize(24).containsOnly(expected);
        assertThat(calls.get()).isEqualTo(expected+1);
        if(expected==0)jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",oa);
      }
    } finally {reads.close();}
  }

  @Test void writeTransactionWithinBoundScopeNeverReusesEarlierValue() {
    String oa="PREVIEW-WRITE-"+UUID.randomUUID();var calls=new AtomicInteger();
    var tx=new TransactionTemplate(transactions);
    try(var reads=new QuotationPreviewReads()) {
      reads.run(()->tx.execute(status->{
        java.util.function.Supplier<Integer> query=()->QuotationPreviewReads.read(this,List.of("oa",oa),()->{
          calls.incrementAndGet();return jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?",Integer.class,oa);
        });
        assertThat(query.get()).isZero();
        jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",oa);
        assertThat(query.get()).isEqualTo(1);status.setRollbackOnly();return null;
      }));
    }
    assertThat(calls.get()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?",Integer.class,oa)).isZero();
  }
}
