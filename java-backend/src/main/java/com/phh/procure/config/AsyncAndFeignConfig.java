package com.phh.procure.config;

import feign.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncAndFeignConfig {

    /**
     * 任务下发线程池：与 Tomcat 线程隔离，长任务不阻塞 HTTP 请求。
     */
    @Bean("agentExecutor")
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public Request.Options feignRequestOptions(AppProperties properties) {
        return new Request.Options(
                properties.getAgent().getConnectTimeoutMs(),
                properties.getAgent().getReadTimeoutMs());
    }
}
