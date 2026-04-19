package cn.iocoder.yudao.framework.common.util.monitor;

/**
 * 链路追踪工具类
 *
 * 考虑到每个 starter 都需要用到该工具类，所以放到 common 模块下的 util 包下
 *
 * @author 芋道源码
 */
public class TracerUtils {

    /**
     * 私有化构造方法
     */
    private TracerUtils() {
    }

    /**
     * 获得链路追踪编号。
     * 当前项目不强制依赖 SkyWalking，因此通过反射兼容获取；如果类不存在则返回空字符串。
     *
     * @return 链路追踪编号
     */
    public static String getTraceId() {
        try {
            Class<?> traceContextClass = Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
            Object traceId = traceContextClass.getMethod("traceId").invoke(null);
            return traceId != null ? traceId.toString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

}
