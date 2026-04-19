package cn.iocoder.yudao.framework.web.core.handler;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 全局异常处理最小兼容实现。
 * 当前仅提供安全过滤器回写 JSON 所需能力，不替代业务模块自己的异常处理逻辑。
 */
@Component
public class GlobalExceptionHandler {

    public CommonResult<?> allExceptionHandler(HttpServletRequest request, Throwable throwable) {
        if (throwable instanceof ServiceException serviceException) {
            return CommonResult.error(serviceException);
        }
        return CommonResult.error(500, throwable.getMessage() == null ? "系统异常" : throwable.getMessage());
    }

}
