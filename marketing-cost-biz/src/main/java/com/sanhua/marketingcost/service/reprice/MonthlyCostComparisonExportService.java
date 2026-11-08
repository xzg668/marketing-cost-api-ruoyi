package com.sanhua.marketingcost.service.reprice;

import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finish the file before committing the HTTP response; a late read failure cannot produce half a workbook. */
@Service
public class MonthlyCostComparisonExportService {
  private static final int PRODUCTS_PER_PAGE=50;
  public record Download(Path path,String filename) implements AutoCloseable {
    @Override public void close() throws IOException {Files.deleteIfExists(path);}
  }
  private final MonthlyCostComparisonService comparison;
  private final MonthlyCostComparisonExcel renderer;
  private final org.mybatis.spring.SqlSessionTemplate session;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  public MonthlyCostComparisonExportService(MonthlyCostComparisonService comparison,MonthlyCostComparisonExcel renderer,
      org.mybatis.spring.SqlSessionTemplate session,com.fasterxml.jackson.databind.ObjectMapper json){
    this.comparison=comparison;this.renderer=renderer;this.session=session;this.json=json;
  }
  @Transactional(readOnly=true,timeout=600)
  public Download export(MonthlyCostComparisonService.Request selection) throws IOException {
    Path file=Files.createTempFile("monthly-cost-comparison-",".xlsx");boolean completed=false;
    try {
      String filename;
      try(var references=new MonthlyCostComparisonSelectionFile(json)) {
        MonthlyCostComparisonService.ExportScope scope;
        try {scope=comparison.selectForExport(selection,references::append);}
        finally {session.clearCache();}
        var first=load(scope,references,1);
        try(var output=Files.newOutputStream(file)) {renderer.write(first,n->load(scope,references,n),output);}
        filename="制造成本明细对比_"+scope.baseline()+"_"+scope.comparison()+".xlsx";
      }
      completed=true;
      return new Download(file,filename);
    } catch(UncheckedIOException failure) {
      throw failure.getCause();
    } finally {if(!completed)Files.deleteIfExists(file);}
  }
  private MonthlyCostComparisonService.Page load(MonthlyCostComparisonService.ExportScope scope,
      MonthlyCostComparisonSelectionFile references,int number) {
    try {return comparison.exportPage(scope,number,PRODUCTS_PER_PAGE,references.readPage(number,PRODUCTS_PER_PAGE));}
    finally {session.clearCache();} // The export transaction keeps its read view, not every page's large snapshots.
  }
}
