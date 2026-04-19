package com.sanhua.marketingcost.common;

/**
 * 旧版响应体。v1.3 起全量替换为若依 {@code cn.iocoder.yudao.framework.common.pojo.CommonResult}。
 * <p>
 * 该类暂保留以免历史代码引用报错，新代码不应再使用；后续清理阶段会彻底删除。
 */
@Deprecated
public class ApiResponse<T> {
  private boolean success;
  private String message;
  private T data;

  public ApiResponse() {
  }

  public ApiResponse(boolean success, String message, T data) {
    this.success = success;
    this.message = message;
    this.data = data;
  }

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "ok", data);
  }

  public static <T> ApiResponse<T> fail(String message) {
    return new ApiResponse<>(false, message, null);
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }
}
