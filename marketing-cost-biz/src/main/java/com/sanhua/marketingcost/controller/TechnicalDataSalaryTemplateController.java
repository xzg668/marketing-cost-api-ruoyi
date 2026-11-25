package com.sanhua.marketingcost.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公共填写模板不依赖任务是否分派或当前模块能否编辑。 */
@RestController
public class TechnicalDataSalaryTemplateController {
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  @GetMapping("/api/v2/technical-data/salary/template")
  public ResponseEntity<byte[]> template() throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename("工时模板2026第一版.xlsx", StandardCharsets.UTF_8).build().toString())
        .body(new ClassPathResource("templates/technical-data/salary.xlsx").getContentAsByteArray());
  }
}
