package com.sanhua.marketingcost.service.reprice;

import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finish the file before committing the HTTP response; a late read failure cannot produce half a workbook. */
@Service
public class MonthlyCostComparisonExportService {
  public record Download(Path path,String filename) implements AutoCloseable {
    @Override public void close() throws IOException {Files.deleteIfExists(path);}
  }
  private final MonthlyCostComparisonService comparison;
  private final MonthlyCostComparisonExcel renderer;
  private final org.mybatis.spring.SqlSessionTemplate session;
  public MonthlyCostComparisonExportService(MonthlyCostComparisonService comparison,MonthlyCostComparisonExcel renderer,
      org.mybatis.spring.SqlSessionTemplate session){this.comparison=comparison;this.renderer=renderer;this.session=session;}
  @Transactional(readOnly=true,timeout=120)
  public Download export(MonthlyCostComparisonService.Request selection) throws IOException {
    var first=load(selection,1);
    Path file=Files.createTempFile("monthly-cost-comparison-",".xlsx");boolean completed=false;
    try {
      try(var output=Files.newOutputStream(file)) {renderer.write(first,n->load(selection,n),output);}
      completed=true;
      return new Download(file,"制造成本明细对比_"+first.comparison().baselineMonth()+"_"+first.comparison().comparisonMonth()+".xlsx");
    } finally {if(!completed)Files.deleteIfExists(file);}
  }
  private MonthlyCostComparisonService.Page load(MonthlyCostComparisonService.Request selection,int number) {
    try {return comparison.compare(page(selection,number));}
    finally {session.clearCache();} // The export transaction keeps its read view, not every page's large snapshots.
  }
  private static MonthlyCostComparisonService.Request page(MonthlyCostComparisonService.Request s,int number){
    return new MonthlyCostComparisonService.Request(s.baselineMonth(),s.comparisonMonth(),s.metrics(),s.oaNo(),s.itemId(),s.productCode(),number,10);
  }
}
