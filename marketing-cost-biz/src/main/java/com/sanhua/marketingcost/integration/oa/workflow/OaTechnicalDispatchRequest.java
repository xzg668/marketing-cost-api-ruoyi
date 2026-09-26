package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 一个请求对应一个原 OA 流程；产品行与模块分工用于生成说明，由报价系统维护。 */
public record OaTechnicalDispatchRequest(
    @NotBlank @Size(max = 128) String requestId,
    @NotBlank @Size(max = 32) String processCode,
    @Size(max = 64) String operatorEmployeeNo,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Product> products) {

  public record Product(
      @NotBlank @Size(max = 128) String rowId,
      @NotBlank @Size(max = 128) String productNo,
      @NotEmpty @Size(max = 9) List<@NotNull @Valid Assignment> assignments) {
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) { unknown(name); }
  }

  public record Assignment(
      @NotBlank @Size(max = 64) String technicianEmployeeNo,
      @NotBlank @Size(max = 64) String technicianName,
      @NotEmpty @Size(max = 9) List<@NotBlank String> moduleTypes) {
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) { unknown(name); }
  }

  @JsonAnySetter
  public void rejectUnknown(String name, JsonNode value) { unknown(name); }

  private static void unknown(String name) {
    throw new IllegalArgumentException("技术分派请求包含未定义字段：" + name);
  }
}
