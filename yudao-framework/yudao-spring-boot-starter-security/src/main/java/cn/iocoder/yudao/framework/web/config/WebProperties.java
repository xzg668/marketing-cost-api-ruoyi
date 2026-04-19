package cn.iocoder.yudao.framework.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Web 模块最小配置兼容类。
 * 当前仅保留安全模块构建 URL 前缀所需字段，避免继续引入完整 web starter。
 */
@ConfigurationProperties(prefix = "yudao.web")
@Component
@Data
public class WebProperties {

    private Api adminApi = new Api("/admin-api");

    private Api appApi = new Api("/app-api");

    @Data
    public static class Api {

        private String prefix;

        public Api() {
        }

        public Api(String prefix) {
            this.prefix = prefix;
        }
    }

}
