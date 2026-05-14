package com.kama.jchatmind.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 环境变量配置类
 * 用于加载 .env 文件中的配置
 */
@Configuration
public class EnvConfig {
    
    @Bean
    public Dotenv dotenv() {
        return Dotenv.configure()
                .ignoreIfMissing()
                .load();
    }
}
