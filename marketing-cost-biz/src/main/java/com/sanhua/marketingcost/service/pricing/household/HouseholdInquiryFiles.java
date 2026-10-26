package com.sanhua.marketingcost.service.pricing.household;

import com.sanhua.marketingcost.service.settlement.SettlementWorkbookStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reuses the existing immutable, SHA-verified archive transport in a separate household directory. */
@Component
public class HouseholdInquiryFiles {
  private final SettlementWorkbookStore archive;
  public HouseholdInquiryFiles(@Value("${price.household.workbooks-root:${quote.technical-data.attachments-root:./data/technical-data}/household}") String root) {
    archive=new SettlementWorkbookStore(root);
  }
  public String save(String unit,byte[] bytes) {
    try { return archive.save(unit,bytes); }
    catch(IllegalStateException failure) { throw new IllegalStateException("家用询价原件归档失败",failure); }
  }
  public byte[] read(String unit,String sha256) {
    try { return archive.read(unit,sha256); }
    catch(IllegalStateException failure) { throw new IllegalStateException("家用询价原件缺失或摘要不符",failure); }
  }
}
