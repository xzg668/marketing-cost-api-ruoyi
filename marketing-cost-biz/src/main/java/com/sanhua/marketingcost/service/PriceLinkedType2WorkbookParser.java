package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.io.InputStream;

/** 将类型 2 Excel 解析成只读中间对象，不写入数据库。 */
public interface PriceLinkedType2WorkbookParser {

  PriceLinkedType2WorkbookParseResult parse(InputStream input, String sourceFileName);
}
