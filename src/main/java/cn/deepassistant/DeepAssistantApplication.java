package cn.deepassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。所有 AgentScope 官方能力都在 {@link cn.deepassistant.config.AgentScopeConfig} 里打开。
 */
@SpringBootApplication
public class DeepAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepAssistantApplication.class, args);
    }
}
