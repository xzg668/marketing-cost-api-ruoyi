package com.sanhua.marketingcost.service.pricing.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class MonthlyPriceFolderCodec {
  private final ObjectMapper json;
  public MonthlyPriceFolderCodec(ObjectMapper json) {
    this.json = json.copy().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
        .addSerializer(BigDecimal.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
  }
  public String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalArgumentException("无法保存文件夹导入依据", e); }
  }
  public <T> T read(String value, Class<T> type) {
    try { return json.readValue(value, type); }
    catch (Exception e) { throw new IllegalArgumentException("文件夹导入依据不可读", e); }
  }
  public List<Long> publications(String value) {
    return value == null ? List.of() : Arrays.asList(read(value, Long[].class));
  }
}
