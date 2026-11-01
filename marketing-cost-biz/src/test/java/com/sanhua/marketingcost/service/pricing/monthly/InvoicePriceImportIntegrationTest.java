package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.invoice.InvoiceWorkbookParserTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.invoice.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

@Tag("integration")
class InvoicePriceImportIntegrationTest {
  static InvoicePriceService service;
  @BeforeAll static void start() throws Exception {
    MonthlyFixedPriceSourceIntegrationTest.database();
    var ds=MonthlyFixedPriceSourceIntegrationTest.jdbc.getDataSource();
    service=new InvoicePriceService(MonthlyFixedPriceSourceIntegrationTest.jdbc,new InvoiceWorkbookParser(),new ObjectMapper(),new DataSourceTransactionManager(ds));
  }
  @AfterAll static void stop(){MonthlyFixedPriceSourceIntegrationTest.stop();}
  static InvoicePriceService.Published publish(String org,String request,byte[] bytes){return service.publish("COMMERCIAL",org,"fixture",request,"任意文件.xlsx",bytes);}
  @Test void importIsIncrementalIdempotentAndPreservesExactOriginalAndHistoricalRows() throws Exception {
    String code="INV-"+UUID.randomUUID(),other=code+"B",request=UUID.randomUUID().toString();
    var bytes=workbook("任意sheet",new String[][]{row(code,"D1","0001","80.38555556"),row(code,"D1","0001","80.38555556"),row(other,"D1","0001","20")});
    var one=publish("210",request,bytes);var replay=publish("210",request,bytes);
    assertThat(replay).isEqualTo(one);assertThat(one.rows()).isEqualTo(2);assertThat(one.duplicates()).isEqualTo(1);
    assertThat(service.original("COMMERCIAL",one.batchId()).bytes()).isEqualTo(bytes);
    var two=publish("210",UUID.randomUUID().toString(),workbook("11月新的名字",new String[][]{row(code,"D1","0001","85")}));
    assertThat(two.batchId()).isGreaterThan(one.batchId());
    assertThat(service.current("COMMERCIAL","210",code,1,20).records()).hasSize(1);
    assertThat(service.current("COMMERCIAL","210",code,1,20).records().getFirst().get("unit_price").toString()).startsWith("85.");
    assertThat(service.current("COMMERCIAL","210",other,1,20).total()).isEqualTo(1);
    assertThat(service.batchRows("COMMERCIAL",one.batchId()).getFirst().unitPrice()).isEqualByComparingTo("80.38555556");
    assertThat(service.history("COMMERCIAL","210",code,1,20).total()).isEqualTo(2);
    assertThat(service.current("HOUSEHOLD","210",code,1,20).total()).isZero();
    assertThatThrownBy(()->service.original("HOUSEHOLD",one.batchId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(()->publish("220",request,bytes)).hasMessageContaining("导入请求已用于");
  }
  @Test void latestBatchIsPerMaterialAndMultipleDocumentsRemainDistinct() throws Exception {
    String code="INV-"+UUID.randomUUID();
    publish("210",UUID.randomUUID().toString(),workbook("一",new String[][]{row(code,"OLD","S1","999")}));
    publish("210",UUID.randomUUID().toString(),workbook("二",new String[][]{row(code,"NEW1","S1","80"),row(code,"NEW2","S1","85")}));
    assertThat(service.current("COMMERCIAL","210",code,1,20).records()).hasSize(2);
    assertThat(MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject("SELECT COUNT(*) FROM lp_invoice_price WHERE material_code=?",Integer.class,code)).isEqualTo(3);
    assertThat(service.current("COMMERCIAL","220",code,1,20).total()).isZero();
  }
  @Test void invalidWorkbookCannotPublishPartialRows() throws Exception {
    String code="INV-"+UUID.randomUUID();
    var bytes=workbook("无效",new String[][]{row(code,"D1","S1","10"),row(code,"D2","S1","-1")});
    assertThatThrownBy(()->publish("210",UUID.randomUUID().toString(),bytes)).hasMessageContaining("第3行");
    assertThat(service.current("COMMERCIAL","210",code,1,20).total()).isZero();
  }
}
