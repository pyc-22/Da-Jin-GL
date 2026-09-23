package com.dajin.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.dajin.system.persistence")
public class DajinApplication {
    public static void main(String[] args) { SpringApplication.run(DajinApplication.class, args); }
}
