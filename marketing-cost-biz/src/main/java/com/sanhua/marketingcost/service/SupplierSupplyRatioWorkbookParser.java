package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import java.io.InputStream;

public interface SupplierSupplyRatioWorkbookParser {
  SupplierSupplyRatioWorkbookParseResult parse(InputStream input, String sourceFileName);
}
