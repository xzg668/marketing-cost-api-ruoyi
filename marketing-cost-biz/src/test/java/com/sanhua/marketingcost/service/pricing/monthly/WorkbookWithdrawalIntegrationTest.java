package com.sanhua.marketingcost.service.pricing.monthly;

import static com.sanhua.marketingcost.service.pricing.WorkbookPriceImportHistory.Kind.*;
import static com.sanhua.marketingcost.service.pricing.invoice.InvoiceWorkbookParserTest.*;
import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.impl.OriginalRangeImportService;
import com.sanhua.marketingcost.service.pricing.WorkbookPriceImportHistory;
import com.sanhua.marketingcost.service.pricing.invoice.InvoicePriceService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.scrap.ScrapPriceImportService;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("integration")
class WorkbookWithdrawalIntegrationTest extends OriginalPricingIntegrationTestBase {
  private static final String BU="COMMERCIAL", OCT="2026-10", NOV="2026-11";
  @Autowired InvoicePriceService invoices;
  @Autowired ScrapPriceImportService scraps;
  @Autowired OriginalRangeImportService ranges;
  @Autowired FinanceFactorPublicationService finance;
  @Autowired WorkbookPriceImportHistory history;
  @Autowired MonthlyPriceStorage storage;
  @Autowired MonthlyPriceDocumentCodec codec;
  @Autowired MonthlyInvoicePriceSource invoiceSource;
  @Autowired MonthlyScrapPriceSource scrapSource;
  @Autowired MonthlyOriginalRangePriceSource rangeSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceService inquiries;
  @Autowired com.sanhua.marketingcost.service.pricing.inquiry.InquiryImportVoidService inquiryVoid;
  @Autowired com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceRepository inquiryRepository;

  @Test void invoiceWithdrawalBlocksOlderDocumentsAndRetiresAdoptionsAndDependencies() throws Exception {
    String code=code("INV");
    uploadInvoice(code,"5","D1","210");
    var wrong=uploadInvoice(code,"10","D2","210");
    uploadInvoice(code,"40","D3","220");
    var r=request(code,OCT,"千克","210");
    var operation=operation(r,c->invoiceSource.read(c,Set.of(r)));
    var old=adopt(operation,r);
    var dependent=new org.springframework.transaction.support.TransactionTemplate(transactions);
    dependent.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    var parent=request(code+"-PARENT",OCT,"件","210");
    long parentId=dependent.execute(status->storage.insertPrice(parent.family(),
        MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any())),
        PriceFact.normal(BigDecimal.ONE,SourceEvidence.ofJson("{\"sourceKind\":\"MAKE\",\"source\":{}}"),Set.of()),
        null,"{\"dependencies\":[{\"monthlyPriceId\":"+old.monthlyPriceId()+"}]}","fixture").id());
    assertThatThrownBy(()->invoices.voidImport(BU,"220",wrong,"other"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    invoices.voidImport(BU,"210",wrong,"finance");
    invoices.voidImport(BU,"210",wrong,"retry");
    assertThat(invoices.current(BU,"210",code,1,20).total()).isZero();
    assertThat(invoices.current(BU,"220",code,1,20).total()).isOne();
    assertThat(jdbc.queryForObject("SELECT disabled FROM lp_material_monthly_price WHERE id=?",Integer.class,parentId)).isOne();
    assertMissing(operation,r,old);
    assertThat(invoices.batchRows(BU,wrong).getFirst().unitPrice()).isEqualByComparingTo("10");
    uploadInvoice(code,"12","D2","210");
    assertReplacement(operation,r,old,"12");
  }

  @Test void scrapWithdrawalRetiresCarriedAdoptionAndReimportUsesNewRule() throws Exception {
    String code=code("SC");
    uploadScrap(code,OCT,5);
    var wrong=uploadScrap(code,NOV,10);
    var r=request(code,"2026-12","KG","210");
    var operation=operation(r,c->scrapSource.read(c,Set.of(r)));
    var old=adopt(operation,r);
    assertThat(old.fact().price()).isEqualByComparingTo("10");
    long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,wrong.importBatchNo());
    history.withdraw(BU,SCRAP_PRICE,id,"finance");
    assertMissing(operation,r,old);
    assertThat(history.original(BU,SCRAP_PRICE,id).bytes()).isNotEmpty();
    var earlier=request(code,OCT,"KG","210");
    assertThat(scrapSource.read(context(earlier),Set.of(earlier)).values().get(earlier).selection().fact().price()).isEqualByComparingTo("5");
    uploadScrap(code,NOV,12);
    assertReplacement(operation,r,old,"12");
  }

  @Test void rangeWithdrawalMasksOlderVersionsPreservesOriginalAndCreatesNewAdoption() throws Exception {
    String code=code("RG");
    String originalMonth="2026-09";
    byte[] base=file("/finance-factor/commercial-original.xlsx");
    var scope=new FinanceFactorImportContext(BU,"210",originalMonth,null,"CU_90000");
    var financePreview=finance.preview(base,"withdrawal-base.xlsx",scope);
    finance.publish(base,"withdrawal-base.xlsx",scope,financePreview.previewFingerprint(),"fixture");
    uploadRange(code,originalMonth,5);
    var wrong=uploadRange(code,originalMonth,10);
    var r=request(code,OCT,"只","210");
    var operation=operation(r,c->rangeSource.read(c,Set.of(r)));
    var old=adopt(operation,r);
    assertThat(old.fact().price()).isEqualByComparingTo("10");
    long id=wrong.batchIds().getFirst();
    history.withdraw(BU,RANGE_ORIGINAL,id,"finance");
    assertMissing(operation,r,old);
    assertThat(history.original(BU,RANGE_ORIGINAL,id).bytes()).isNotEmpty();
    uploadRange(code,originalMonth,12);
    assertReplacement(operation,r,old,"12");
  }

  @Test void oneInvoiceCanBeWithdrawnWhileAnotherDocumentInItsBatchStillSuppliesPrice() throws Exception {
    String code=code("PER-INV"), peer=code+"-PEER";
    long batch=invoices.publish(BU,"210","fixture",UUID.randomUUID().toString(),"single-invoice.xlsx",
        workbook("发票",new String[][]{row(code,"D1","S1","10"),row(code,"D2","S1","8"),row(peer,"D3","S1","11")})).batchId();
    var request=request(code,OCT,"千克","210");
    var op=operation(request,c->invoiceSource.read(c,Set.of(request)));
    var first=adopt(op,request);
    long id=jdbc.queryForObject("SELECT id FROM lp_invoice_price WHERE batch_id=? AND document_no='D1'",Long.class,batch);
    invoices.voidRow(BU,"210",id,batch);
    assertThat(op.lookup(Set.of(request)).missing()).containsExactly(request);
    assertReplacement(op,request,first,"8");
    assertThat(invoices.current(BU,"210",peer,1,20).records()).hasSize(1);
    assertThat(invoices.batchRows(BU,batch)).hasSize(3);
    uploadInvoice(code,"12","D1","210");
    assertThatThrownBy(()->invoices.voidRow(BU,"210",id,batch))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertReplacement(op,request,first,"12");
  }

  @Test void aSupplierlessModelInquiryCanBeWithdrawnWithoutVoidingItsWorkbook() throws Exception {
    String model=code("PER-MODEL"), peer=model+"-OTHER";
    var bytes=fixedInquiryWorkbook(model,peer);
    long batch=inquiries.publish(BU,OCT,"fixture",UUID.randomUUID().toString(),"single-inquiry.xlsx",bytes).batchId();
    var all=inquiryRepository.batch(BU,batch);
    var selected=all.stream().filter(row->model.equals(row.rule().specification())).findFirst().orElseThrow();
    inquiryVoid.voidRow(BU,selected.id(),"fixture");
    assertThat(inquiryRepository.current(BU,OCT,false)).noneMatch(row->row.id()==selected.id())
        .anyMatch(row->peer.equals(row.rule().specification()));
    assertThat(inquiryRepository.batch(BU,batch)).anySatisfy(row->{
      assertThat(row.id()).isEqualTo(selected.id());
      assertThat(row.batchStatus()).isEqualTo("VOIDED");
    });
    assertThat(jdbc.queryForObject("SELECT status FROM lp_inquiry_import_batch WHERE id=?",String.class,batch)).isEqualTo("PUBLISHED");
    long newBatch=inquiries.publish(BU,OCT,"fixture",UUID.randomUUID().toString(),"single-inquiry.xlsx",bytes).batchId();
    assertThat(inquiryRepository.current(BU,OCT,false)).anyMatch(row->model.equals(row.rule().specification()) && row.batchId()==newBatch);
  }

  @Test void oneFactorBandWithdrawalLeavesSiblingBandsAndReimportCanReplaceIt() throws Exception {
    String code=code("PER-BAND"), month="2026-08";
    byte[] base=file("/finance-factor/commercial-original.xlsx");
    var scope=new FinanceFactorImportContext(BU,"210",month,null,"CU_90000");
    var financePreview=finance.preview(base,"single-band-base.xlsx",scope);
    finance.publish(base,"single-band-base.xlsx",scope,financePreview.previewFingerprint(),"fixture");
    var batch=uploadRange(code,month,10);
    var request=request(code,month,"只","210");
    var op=operation(request,c->rangeSource.read(c,Set.of(request)));
    var old=adopt(op,request);
    var evidence=new com.fasterxml.jackson.databind.ObjectMapper().readTree(old.fact().evidence().json());
    long band=evidence.path("source").path("selectedBandId").asLong();
    long original=evidence.path("source").path("selected").path("id").asLong();
    history.withdrawRangeRow(BU,band);
    assertMissing(op,request,old);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_range_item WHERE factor_rule_id=? AND row_voided=0",Integer.class,original)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT status FROM lp_factor_upload_batch WHERE id=?",String.class,batch.batchIds().getFirst())).isEqualTo("PUBLISHED");
    uploadRange(code,month,12);
    assertReplacement(op,request,old,"12");
  }

  private static byte[] fixedInquiryWorkbook(String... models) throws Exception {
    try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("询价");
      var names=List.of("料号","规格型号","供方名称","价格类型","批量价总价(元)","不含税价格","计量单位");
      var header=sheet.createRow(0);
      for(int i=0;i<names.size();i++)header.createCell(i).setCellValue(names.get(i));
      for(int i=0;i<models.length;i++){
        var row=sheet.createRow(i+1);
        var fields=List.of("",models[i],"","固定价","7.375","7.375","件");
        for(int j=0;j<fields.size();j++)row.createCell(j).setCellValue(fields.get(j));
      }
      book.write(out);
      return out.toByteArray();
    }
  }

  private long uploadInvoice(String code,String amount,String document,String org) throws Exception {
    var bytes=workbook("发票",new String[][]{row(code,document,"S1",amount)});
    return invoices.publish(BU,org,"fixture",UUID.randomUUID().toString(),"invoice.xlsx",bytes).batchId();
  }

  private ScrapPriceImportService.ImportResult uploadScrap(String code,String month,int amount) throws Exception {
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(file("/scrap/original-20261017.xlsx")));
        var out=new ByteArrayOutputStream()) {
      var sheet=book.getSheet("废料清单");
      for(int i=sheet.getLastRowNum();i>1;i--) {
        var row=sheet.getRow(i);
        if(row!=null)sheet.removeRow(row);
      }
      sheet.getRow(1).getCell(0).setCellValue(code);
      sheet.getRow(1).getCell(5).setCellValue(amount);
      book.write(out);
      byte[] bytes=out.toByteArray();
      var preview=scraps.preview(bytes,"scrap.xlsx",BU,month);
      assertThat(preview.canConfirm()).as(preview.errors().toString()).isTrue();
      return scraps.confirm(bytes,"scrap.xlsx",BU,month,preview.previewFingerprint(),"fixture");
    }
  }

  private OriginalRangeImportService.Publication uploadRange(String code,String month,int amount) throws Exception {
    byte[] bytes=rangeWorkbook(code,amount);
    var preview=ranges.preview(new OriginalRangeImportService.Command(bytes,"range.xlsx",BU,month,null,null));
    assertThat(preview.canConfirm()).as(preview.errors().toString()).isTrue();
    return ranges.confirm(new OriginalRangeImportService.Command(bytes,"range.xlsx",BU,month,preview.fileSha256(),preview.previewFingerprint()));
  }

  public static byte[] rangeWorkbook(String code,int amount) throws Exception {
    try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var main=book.createSheet("ImportData");
      var header=main.createRow(0);
      String[] names={"组织","物料代码","供应商名称","供应商代码","规格型号","单位","单价","是否含税"};
      for(int i=0;i<names.length;i++)header.createCell(i).setCellValue(names[i]);
      var row=main.createRow(1);
      String[] values={"210",code,"测试供应商有限公司","S01","SPEC","只"};
      for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
      row.createCell(6).setCellValue(99);row.createCell(7).setCellValue(false);
      var matrix=book.createSheet("矩阵");matrix.createRow(0).createCell(0).setCellValue("铜价区间表");
      var h=matrix.createRow(1);h.createCell(0).setCellValue("U9代码");h.createCell(1).setCellValue("图号");h.createCell(2).setCellValue("供应商名称");
      var tax=matrix.createRow(2);var data=matrix.createRow(3);
      data.createCell(0).setCellValue(code);data.createCell(1).setCellValue("SPEC");data.createCell(2).setCellValue("测试供应商");
      for(int i=0;i<4;i++) {
        int c=i*2+3;
        h.createCell(c).setCellValue((90000+i*5000)+"-"+(94999+i*5000));
        tax.createCell(c).setCellValue("不含税");tax.createCell(c+1).setCellValue("含13%税价");
        data.createCell(c).setCellValue(amount+i);data.createCell(c+1).setCellValue((amount+i)*1.13);
      }
      book.write(out);return out.toByteArray();
    }
  }

  private static String code(String prefix) {return prefix+"-"+UUID.randomUUID().toString().substring(0,8);}
  private byte[] file(String path) throws IOException {
    try(var in=getClass().getResourceAsStream(path)){return Objects.requireNonNull(in).readAllBytes();}
  }
  private static MonthlyPriceRequest request(String code,String month,String unit,String org) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),BU,code,null,unit,"CNY",TaxBasis.EXCLUDED,null),
        org,NonCuConditions.empty(),BigDecimal.ONE);
  }
  private static Context context(MonthlyPriceRequest r) {
    return new Context(BU,"WITHDRAWAL-TEST",1L,"TOP",r.family().month(),r.family().month().atEndOfMonth().atStartOfDay(),"fixture",r.priceOrgCode());
  }
  private MonthlyMaterialPriceService operation(MonthlyPriceRequest r,Function<Context,Candidates> reader) {
    return new MonthlyMaterialPriceService(storage,transactions,codec,(context,requests,dependencies)->reader.apply(context));
  }
  private static Selection adopt(MonthlyMaterialPriceService service,MonthlyPriceRequest r) {
    var output=service.resolveCandidates(context(r),Set.of(r));
    assertThat(output.problems()).isEmpty();
    var candidate=output.values().get(r).selection();
    return service.adopt(context(r),new MonthlyAdoptionPlan(Map.of(r,candidate),List.of(new Use("top/part",r,BigDecimal.ONE)),Set.of())).plan().selections().get(r);
  }
  private static void assertMissing(MonthlyMaterialPriceService op,MonthlyPriceRequest r,Selection old) {
    assertThat(op.lookup(Set.of(r)).missing()).containsExactly(r);
    assertThat(op.resolveCandidates(context(r),Set.of(r)).values()).isEmpty();
    assertThat(op.readByIds(BU,Set.of(old.monthlyPriceId())).get(old.monthlyPriceId())).isEqualTo(old);
  }
  private static void assertReplacement(MonthlyMaterialPriceService op,MonthlyPriceRequest r,Selection old,String amount) {
    var fresh=adopt(op,r);
    assertThat(fresh.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(fresh.fact().price()).isEqualByComparingTo(amount);
    assertThat(op.readByIds(BU,Set.of(old.monthlyPriceId())).get(old.monthlyPriceId())).isEqualTo(old);
  }
}
