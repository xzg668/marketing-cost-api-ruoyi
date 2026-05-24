package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelTemplateInfoResponse;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class QuoteExcelTemplateServiceImpl implements QuoteExcelTemplateService {

  @Override
  public List<QuoteExcelTemplateInfoResponse> listTemplates() {
    return Arrays.stream(QuoteExcelTemplateType.values()).map(this::toResponse).toList();
  }

  @Override
  public QuoteExcelTemplateFile getTemplate(String templateType) {
    QuoteExcelTemplateType type;
    try {
      type = QuoteExcelTemplateType.fromCode(templateType);
    } catch (IllegalArgumentException ex) {
      throw new QuoteIngestException(ex.getMessage());
    }
    ClassPathResource resource = new ClassPathResource(type.getResourcePath());
    if (!resource.exists()) {
      throw new QuoteIngestException("报价单模板文件不存在: " + type.getCode());
    }
    try (InputStream inputStream = resource.getInputStream()) {
      return new QuoteExcelTemplateFile(type, inputStream.readAllBytes());
    } catch (IOException ex) {
      throw new QuoteIngestException("读取报价单模板失败: " + ex.getMessage());
    }
  }

  private QuoteExcelTemplateInfoResponse toResponse(QuoteExcelTemplateType type) {
    QuoteExcelTemplateInfoResponse response = new QuoteExcelTemplateInfoResponse();
    response.setTemplateType(type.getCode());
    response.setProcessCode(type.getProcessCode());
    response.setQuoteScenario(type.getQuoteScenario());
    response.setDisplayName(type.getDisplayName());
    response.setFileName(type.getFileName());
    return response;
  }
}
