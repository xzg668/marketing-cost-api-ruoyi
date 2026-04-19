package com.sanhua.marketingcost.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * T34 Actuator 安全配置校验。
 * <p>
 * 不启动完整上下文（避免需要 Actuator auto-config + 数据库），仅校验：
 * <ul>
 *   <li>类注解 {@code @Configuration} 正确</li>
 *   <li>构造器依赖：{@link JwtAuthenticationFilter} + {@link ObjectMapper}</li>
 *   <li>{@code actuatorSecurityFilterChain} 方法标注 {@link Order}，早于主链执行</li>
 *   <li>返回类型为 {@link SecurityFilterChain}</li>
 *   <li>application.yml 配置正确暴露 6 个端点 + 启用 ADMIN 可见详情</li>
 * </ul>
 */
class ActuatorSecurityConfigTest {

    private static String yml;

    @BeforeAll
    static void loadYml() throws Exception {
        Path p = Paths.get("src/main/resources/application.yml");
        if (!Files.exists(p)) {
            p = Paths.get("marketing-cost-biz/src/main/resources/application.yml");
        }
        assertTrue(Files.exists(p), "application.yml 应存在: " + p.toAbsolutePath());
        yml = Files.readString(p);
    }

    // ========== 配置类结构 ==========

    @Test
    @DisplayName("类标注 @Configuration")
    void classAnnotatedConfiguration() {
        assertNotNull(ActuatorSecurityConfig.class.getAnnotation(Configuration.class),
                "ActuatorSecurityConfig 应标注 @Configuration");
    }

    @Test
    @DisplayName("构造器依赖 JwtAuthenticationFilter + ObjectMapper 且可实例化")
    void constructorInjectsDependencies() throws Exception {
        Constructor<?>[] ctors = ActuatorSecurityConfig.class.getConstructors();
        assertEquals(1, ctors.length, "应只有一个 public 构造器");
        Class<?>[] params = ctors[0].getParameterTypes();
        assertEquals(2, params.length);
        assertTrue(params[0].equals(JwtAuthenticationFilter.class)
                        || params[1].equals(JwtAuthenticationFilter.class),
                "参数必须包含 JwtAuthenticationFilter");
        assertTrue(params[0].equals(ObjectMapper.class)
                        || params[1].equals(ObjectMapper.class),
                "参数必须包含 ObjectMapper");

        // 实例化确认
        ActuatorSecurityConfig cfg = new ActuatorSecurityConfig(
                mock(JwtAuthenticationFilter.class), new ObjectMapper());
        assertNotNull(cfg);
    }

    @Test
    @DisplayName("actuatorSecurityFilterChain 带 @Order —— 必须早于主链")
    void filterChainHasOrder() throws NoSuchMethodException {
        Method m = ActuatorSecurityConfig.class
                .getDeclaredMethod("actuatorSecurityFilterChain",
                        org.springframework.security.config.annotation.web.builders.HttpSecurity.class);
        Order order = m.getAnnotation(Order.class);
        assertNotNull(order, "actuatorSecurityFilterChain 必须标注 @Order");
        // 早于主链（主链默认 Ordered 约为 Integer.MAX_VALUE）—— 任何小于 Integer.MAX_VALUE 的值都可
        assertTrue(order.value() < Integer.MAX_VALUE,
                "Order 值应小于主链默认 order，当前=" + order.value());
        assertEquals(SecurityFilterChain.class, m.getReturnType(),
                "必须返回 SecurityFilterChain");
    }

    // ========== application.yml 校验 ==========

    @Test
    @DisplayName("application.yml 暴露 6 个 actuator 端点")
    void exposesSixEndpoints() {
        // 检查 include 行 —— 以 yaml 串形式出现
        assertTrue(yml.contains("include: health,info,metrics,loggers,env,threaddump")
                        || yml.matches("(?s).*include:\\s*health.*info.*metrics.*loggers.*env.*threaddump.*"),
                "应暴露 health/info/metrics/loggers/env/threaddump 六个端点");
    }

    @Test
    @DisplayName("health.show-details 为 when_authorized —— 详情仅登录可见")
    void healthShowDetailsAuthorized() {
        assertTrue(yml.matches("(?s).*health:[\\s\\S]*?show-details:\\s*when_authorized.*"),
                "endpoint.health.show-details 必须为 when_authorized");
    }

    @Test
    @DisplayName("logging.file.path 通过 ${LOG_PATH} 环境变量配置")
    void loggingFilePathConfigured() {
        assertTrue(yml.contains("${LOG_PATH"),
                "logging.file.path 应引用 ${LOG_PATH} 环境变量（默认 ./logs）");
    }
}
