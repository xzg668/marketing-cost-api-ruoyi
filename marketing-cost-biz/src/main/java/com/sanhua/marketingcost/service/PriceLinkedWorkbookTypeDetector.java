package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import java.io.InputStream;

/** 根据工作簿可见 Sheet 的内容识别联动价模板类型。 */
public interface PriceLinkedWorkbookTypeDetector {

  PriceLinkedWorkbookDetectionResult detect(InputStream input);

  /**
   * 文件名仅保留为调用契约，识别器有意忽略该参数，防止通过文件名推断模板。
   */
  default PriceLinkedWorkbookDetectionResult detect(InputStream input, String originalFilename) {
    return detect(input);
  }
}
