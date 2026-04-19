package com.sanhua.marketingcost.config;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * T34：Actuator 端点安全配置。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>独立 {@link SecurityFilterChain}，{@link Order} 优先于主链，仅匹配 actuator 端点路径</li>
 *   <li>{@code /actuator/health} 与 {@code /actuator/info} 公开 —— K8s / 监控系统探活无需 Token</li>
 *   <li>其他端点（env / loggers / metrics / threaddump）需 ADMIN 角色 —— 运维排障专用</li>
 *   <li>复用 JWT 过滤器解析 Bearer Token，保持与业务接口一致的认证方式</li>
 * </ul>
 * 未在此做"内网 IP 白名单"—— 生产部署通过 Nginx / 云厂商 SG 在网络层限制，避免应用层
 * 硬编码网段导致的维护成本。
 */
@Configuration
public class ActuatorSecurityConfig {

    /** admin 角色名 —— 与 Spring Security 的 {@code ROLE_ADMIN} 对齐 */
    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public ActuatorSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                                  ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Actuator 专用过滤链。必须早于主 SecurityFilterChain 执行，否则 /actuator/** 会被主链
     * 的 .anyRequest().authenticated() 拦截，导致 /health 非登录访问 401。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // securityMatcher 决定此链仅对 /actuator/** 生效
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health / info 公开 —— 供 K8s probe、监控探活使用
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        // 其他运维端点（env/loggers/metrics/threaddump）需 ADMIN
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(ROLE_ADMIN)
                )
                // 复用主链 JWT 过滤器，便于 Bearer Token 解析
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getOutputStream(),
                                    CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(),
                                            "未认证，请先登录"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getOutputStream(),
                                    CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN.getCode(),
                                            "权限不足"));
                        })
                );
        return http.build();
    }
}
