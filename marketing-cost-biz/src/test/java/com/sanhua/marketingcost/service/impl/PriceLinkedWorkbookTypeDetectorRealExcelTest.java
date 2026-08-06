package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-02 真实联动价 Excel 类型识别")
class PriceLinkedWorkbookTypeDetectorRealExcelTest {

  private static final Path STANDARD_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/demo9/联动价.xls");
  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  private final PriceLinkedWorkbookTypeDetectorImpl detector =
      new PriceLinkedWorkbookTypeDetectorImpl();

  @Test
  @DisplayName("真实原联动价模板稳定识别为 STANDARD")
  void detectsRealStandardWorkbook() throws Exception {
    byte[] bytes = readRequiredSample(STANDARD_SAMPLE);

    PriceLinkedWorkbookDetectionResult result = detector.detect(
        new ByteArrayInputStream(bytes), STANDARD_SAMPLE.getFileName().toString());

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.STANDARD);
    assertThat(result.getStandardCandidateSheets()).hasSize(1);
    assertThat(result.getType2CandidateSheets()).isEmpty();
  }

  @Test
  @DisplayName("真实类型2模板稳定识别为 TYPE2")
  void detectsRealType2Workbook() throws Exception {
    byte[] bytes = readRequiredSample(TYPE2_SAMPLE);

    PriceLinkedWorkbookDetectionResult result = detector.detect(
        new ByteArrayInputStream(bytes), TYPE2_SAMPLE.getFileName().toString());

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
    assertThat(result.getType2CandidateSheets()).hasSize(1);
    assertThat(result.getStandardCandidateSheets()).hasSize(1);
  }

  @Test
  @DisplayName("真实类型2文件改名后识别结果不变")
  void realType2DetectionDoesNotDependOnFilename() throws Exception {
    byte[] bytes = readRequiredSample(TYPE2_SAMPLE);

    PriceLinkedWorkbookDetectionResult original = detector.detect(
        new ByteArrayInputStream(bytes), TYPE2_SAMPLE.getFileName().toString());
    PriceLinkedWorkbookDetectionResult renamed = detector.detect(
        new ByteArrayInputStream(bytes), "完全改名且不带任何类型提示.xls");

    assertThat(renamed.getType()).isEqualTo(original.getType());
    assertThat(renamed.getStandardCandidateSheets())
        .isEqualTo(original.getStandardCandidateSheets());
    assertThat(renamed.getType2CandidateSheets())
        .isEqualTo(original.getType2CandidateSheets());
  }

  private byte[] readRequiredSample(Path path) throws Exception {
    assertThat(Files.exists(path)).as("真实样例存在: " + path).isTrue();
    return Files.readAllBytes(path);
  }
}
