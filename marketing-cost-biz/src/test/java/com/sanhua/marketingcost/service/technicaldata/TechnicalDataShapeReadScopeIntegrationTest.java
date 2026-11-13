package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class TechnicalDataShapeReadScopeIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MaterialPriceSourceService catalog;
  JdbcTemplate measured;
  QuoteMaterialShapeInputs shapes;
  String code;
  Long itemId;
  @BeforeEach void setup() {
    MaterialPriceSourceTestData.schema(jdbc);
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
        new org.springframework.core.io.ClassPathResource("db/V380__quotation_material_shape_selection.sql"),
        new org.springframework.core.io.ClassPathResource("db/V381__quotation_order_material_shape.sql")).execute(jdbc.getDataSource());
    code="READ-"+UUID.randomUUID().toString().substring(0,8);
    MaterialPriceSourceTestData.fixed(jdbc,"COMMERCIAL","210",code,"2026-11","5");
    MaterialPriceSourceTestData.linked(jdbc,"COMMERCIAL","210",code,"2026-11");
    MaterialPriceSourceTestData.tree(jdbc,code,"2026-11");
    jdbc.update("UPDATE lp_quote_effective_bom_node SET effective_material_shape='PURCHASE' WHERE material_code=?",code);
    itemId=jdbc.queryForObject("SELECT oa_form_item_id FROM lp_quote_costing_workspace WHERE current_bom_build_batch_id=?",Long.class,"CAT-TREE-"+code+"2026-11");
    catalog.refresh("COMMERCIAL","2026-11","TEST");
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change("2026-11","210",code,"FIXED","PURCHASE",0L),"TEST");
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change("2026-11","210",code,"LINKED","OUTSOURCE",0L),"TEST");
    measured=spy(jdbc);shapes=new QuoteMaterialShapeInputs(measured);
  }
  @AfterEach void removeDisposableTable(){jdbc.execute("DROP TABLE lp_quote_order_material_shape");}
  TransactionTemplate transaction(boolean readOnly,boolean independent) {
    var tx=new TransactionTemplate(transactions);tx.setReadOnly(readOnly);
    if(independent)tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }
  String fixedShape(){return shapes.read("COMMERCIAL","2026-11",itemId,Set.of(code)).sources("210",code).stream().filter(s->s.sourceType().equals("FIXED")).findFirst().orElseThrow().shape();}
  void setFixed(String value){jdbc.update("UPDATE lp_material_price_attribute SET material_attribute=?,row_version=row_version+1 WHERE material_code=? AND price_source_type='FIXED'",value,code);}
  @Test void repeatedBasePriceReadsWithinOnePreviewDoNotReloadTheSameShapeInputs() {
    var context=new MonthlyMaterialPrices.Context("COMMERCIAL","READ-OA",itemId,"CAT-TOP",YearMonth.of(2026,11),LocalDateTime.of(2026,11,13,0,0),"admin","210");
    clearInvocations(measured);
    transaction(true,false).execute(status->{
      for(int i=1;i<=120;i++) {
        var item=new MonthlyQuotationPrices.Item(code,"210","只",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.valueOf(i),MonthlyQuotationPrices.Purpose.MATERIAL);
        String scope=shapes.priceScopes(context,List.of(item)).get(item);
        assertThat(scope).isNotNull();assertThat(MaterialShapePriceScope.allows(scope,"FIXED")).isTrue();assertThat(MaterialShapePriceScope.allows(scope,"LINKED")).isFalse();
      }
      return null;
    });
    long reads=mockingDetails(measured).getInvocations().stream().filter(i->i.getMethod().getName().startsWith("query")).count();
    assertThat(reads).as("120次同一产品的基础取价，JdbcTemplate读取调用次数应有界").isPositive().isLessThanOrEqualTo(60);
  }
  @Test void nextPreviewAndRequiresNewSeeNewClassificationButOuterPreviewKeepsItsOwnSnapshot() {
    transaction(true,false).execute(status->{
      assertThat(fixedShape()).isEqualTo("PURCHASE");
      transaction(false,true).execute(inner->{setFixed("OUTSOURCE");return null;});
      transaction(true,true).execute(inner->{assertThat(fixedShape()).isEqualTo("OUTSOURCE");return null;});
      assertThat(fixedShape()).isEqualTo("PURCHASE");return null;
    });
    transaction(true,false).execute(status->{assertThat(fixedShape()).isEqualTo("OUTSOURCE");return null;});
  }
  @Test void writesAndNonTransactionalReadsRemainFreshAndRollbackDiscardsPreviewSnapshot() {
    assertThat(fixedShape()).isEqualTo("PURCHASE");setFixed("OUTSOURCE");assertThat(fixedShape()).isEqualTo("OUTSOURCE");
    transaction(false,false).execute(status->{setFixed("PURCHASE");assertThat(fixedShape()).isEqualTo("PURCHASE");setFixed("OUTSOURCE");assertThat(fixedShape()).isEqualTo("OUTSOURCE");return null;});
    assertThatThrownBy(()->transaction(true,false).execute(status->{assertThat(fixedShape()).isEqualTo("OUTSOURCE");throw new IllegalStateException("rollback preview");})).hasMessageContaining("rollback preview");
    setFixed("PURCHASE");transaction(true,false).execute(status->{assertThat(fixedShape()).isEqualTo("PURCHASE");return null;});
  }
}
