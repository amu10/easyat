package io.github.easyat.example.local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地 AT 示例的 Spring Boot 启动类。
 *
 * <p>启动后自带的 {@code easy-at-spring-boot-starter} 会自动装配 AT 所需的核心组件
 * （全局事务管理器、undo 仓储等），本示例仅演示「单库内」一笔转账事务的提交与回滚。
 * 应用默认端口通常由配置文件指定，启动后访问 {@code /demo/transfer} 即可体验。
 */
@SpringBootApplication
public class LocalDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocalDemoApplication.class, args);
    }
}
