package com.mio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AiConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean(name = "outputJudgeExecutor")
    public Executor outputJudgeExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "ontologyActivationExecutor")
    public Executor ontologyActivationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ontology-activation-");
        executor.initialize();
        return executor;
    }

    /**
     * shadow 생성 전용 (#481). 의도적으로 작게 잡는다 — shadow 는 관측용 부하라 밀리면
     * 버려야 하고(포화 시 TaskRejectedException → 러너가 rejected 로 계측), 본 트래픽의
     * 리소스를 다투면 안 된다.
     */
    @Bean(name = "shadowGenerationExecutor")
    public Executor shadowGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("shadow-generation-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiDecisionLoggerExecutor")
    public Executor aiDecisionLoggerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-logger-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
