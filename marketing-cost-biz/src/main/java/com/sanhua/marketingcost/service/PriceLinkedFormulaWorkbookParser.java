package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import java.io.InputStream;

public interface PriceLinkedFormulaWorkbookParser {
  LinkedFormulaWorkbookParseResult parse(InputStream input, String sourceFileName);
}
