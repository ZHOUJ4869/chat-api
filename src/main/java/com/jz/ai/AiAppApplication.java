package com.jz.ai;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;


@EnableCaching
@SpringBootApplication
@MapperScan("com.jz.ai.mapper")
@ConfigurationPropertiesScan(basePackages = "com.jz.ai")
public class AiAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAppApplication.class);
    }
}
