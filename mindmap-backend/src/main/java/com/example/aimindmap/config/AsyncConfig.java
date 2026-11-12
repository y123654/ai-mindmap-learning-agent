package com.example.aimindmap.config;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.importpdf.PdfProperties;
import com.example.aimindmap.mindmap.MindMapProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 阶段 3：同步任务后台执行（§5.2 / §13.3 AI 调用不放在数据库长事务中）。
 * 阶段 5：注册 PDF 导入配置（修订第 28 条）。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({AiProperties.class, PdfProperties.class, MindMapProperties.class})
public class AsyncConfig {

    /** 同步任务专用线程池：第一版本机使用，队列很小即可，满后由调用线程兜底执行。 */
    @Bean("syncJobTaskExecutor")
    public Executor syncJobTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("sync-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
