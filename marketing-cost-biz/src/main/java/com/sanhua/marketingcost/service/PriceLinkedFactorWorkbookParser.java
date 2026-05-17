package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import java.io.InputStream;

public interface PriceLinkedFactorWorkbookParser {
  FactorWorkbookParseResult parse(InputStream input, String sourceFileName);
}
