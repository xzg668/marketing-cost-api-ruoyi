package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Identity;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A request-owned list of compact references; large cost snapshots never enter this file or its buffer. */
final class MonthlyCostComparisonSelectionFile implements AutoCloseable {
  private final ObjectMapper json;
  private final Path path;
  private BufferedWriter writer;
  private BufferedReader reader;
  private Selected pending;
  private int page;

  MonthlyCostComparisonSelectionFile(ObjectMapper json) throws IOException {
    this.json=json;
    path=Files.createTempFile("monthly-cost-comparison-selection-",".jsonl");
    try {writer=Files.newBufferedWriter(path);}
    catch(IOException failure) {
      try {Files.deleteIfExists(path);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
      throw failure;
    }
  }
  void append(Selected selected) {
    if(writer==null)throw new IllegalStateException("导出范围已结束写入");
    try {writer.write(json.writeValueAsString(selected));writer.newLine();}
    catch(IOException failure){throw new UncheckedIOException(failure);}
  }
  List<Selected> readPage(int number,int size) {
    if(number!=page+1 || size<1 || size>100)throw new IllegalArgumentException("导出必须顺序读取完整对象分页");
    try {
      if(writer!=null) {writer.close();writer=null;reader=Files.newBufferedReader(path);}
      var selected=new ArrayList<Selected>();Identity previous=null;int objects=0;
      while(true) {
        Selected next;
        if(pending!=null){next=pending;pending=null;}
        else {String line=reader.readLine();if(line==null)break;next=json.readValue(line,Selected.class);}
        var identity=next.identity();
        if(!identity.equals(previous)) {
          if(objects==size){pending=next;break;}
          objects++;previous=identity;
        }
        selected.add(next);
      }
      page=number;return List.copyOf(selected);
    } catch(IOException failure){throw new UncheckedIOException(failure);}
  }
  @Override public void close() throws IOException {
    try {if(writer!=null)writer.close();}
    finally {
      try {if(reader!=null)reader.close();}
      finally {Files.deleteIfExists(path);}
    }
  }
}
