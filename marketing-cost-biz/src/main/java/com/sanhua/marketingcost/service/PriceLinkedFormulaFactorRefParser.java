package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FormulaFactorRef;
import java.util.List;

public interface PriceLinkedFormulaFactorRefParser {
  List<FormulaFactorRef> parse(String priceCellFormula);
}
