package com.sanhua.marketingcost.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T34 日志规范静态校验。
 * <p>
 * 不启动 Spring 上下文，直接读取 {@code logback-spring.xml} 文本校验关键配置：
 * <ul>
 *   <li>日志模式包含 {@code %X{traceId}}（MDC 占位符）</li>
 *   <li>文件滚动策略按天 ({@code %d{yyyy-MM-dd}})</li>
 *   <li>ERROR 日志独立文件（带 LevelFilter）</li>
 *   <li>文件路径使用 {@code ${LOG_DIR}} 变量 —— 允许运维覆盖</li>
 * </ul>
 */
class LogbackConfigTest {

    private static String xml;

    @BeforeAll
    static void loadXml() throws Exception {
        Path p = Paths.get("src/main/resources/logback-spring.xml");
        if (!Files.exists(p)) {
            p = Paths.get("marketing-cost-biz/src/main/resources/logback-spring.xml");
        }
        assertTrue(Files.exists(p), "logback-spring.xml 应存在: " + p.toAbsolutePath());
        xml = Files.readString(p);
    }

    @Test
    @DisplayName("CONSOLE 模式含 traceId MDC 占位符")
    void consolePatternContainsTraceId() {
        assertTrue(xml.contains("%X{traceId"),
                "日志模式必须含 %X{traceId} 以输出 MDC 中的 traceId");
    }

    @Test
    @DisplayName("文件滚动策略按天（%d{yyyy-MM-dd}）")
    void fileRollingByDay() {
        assertTrue(xml.contains("%d{yyyy-MM-dd}"),
                "滚动文件名应按天分片");
    }

    @Test
    @DisplayName("ERROR 独立 appender 且带 LevelFilter=ERROR")
    void errorAppenderSeparate() {
        assertTrue(xml.contains("ERROR_FILE"),
                "应存在 ERROR_FILE appender");
        assertTrue(xml.contains("LevelFilter"),
                "ERROR_FILE 必须配置 LevelFilter");
        assertTrue(xml.contains("<level>ERROR</level>"),
                "LevelFilter 过滤级别应为 ERROR");
        assertTrue(xml.contains("error"),
                "文件名应包含 error 标识");
    }

    @Test
    @DisplayName("日志路径使用 ${LOG_DIR} 环境变量")
    void logDirEnvVariable() {
        assertTrue(xml.contains("${LOG_DIR"),
                "日志输出目录应通过 ${LOG_DIR} 环境变量控制");
    }

    @Test
    @DisplayName("MDC traceId 在 JSON encoder 中被包含")
    void jsonEncoderIncludesTraceId() {
        assertTrue(xml.contains("includeMdcKeyName>traceId"),
                "LogstashEncoder 必须 includeMdcKeyName=traceId");
    }

    @Test
    @DisplayName("非 dev profile 挂载 FILE_JSON + ERROR_FILE 两个文件 appender")
    void nonDevProfileWiresFileAppenders() {
        assertTrue(xml.contains("<springProfile name=\"!dev\">"),
                "应存在 !dev profile 分支");
        // 粗校验：!dev 段内同时引用两个 appender
        int start = xml.indexOf("<springProfile name=\"!dev\">");
        int end = xml.indexOf("</springProfile>", start);
        assertTrue(start > 0 && end > start, "!dev profile 段格式异常");
        String nonDev = xml.substring(start, end);
        assertTrue(nonDev.contains("FILE_JSON") && nonDev.contains("ERROR_FILE"),
                "!dev profile 应同时挂载 FILE_JSON 和 ERROR_FILE");
    }
}
