package com.example.aimindmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AiMindMapApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMindMapApplication.class, args);
    }
}