package com.mio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 스케줄링 활성화는 {@link com.mio.config.SchedulingConfig} 로 분리했다 (이슈 #402).
 * 테스트 컨텍스트에서 프로덕션 스케줄러가 자동 실행되지 않게 하기 위한 조치이며,
 * 프로덕션·로컬 실행에서는 기본값으로 그대로 켜진다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class MioApplication {
    public static void main(String[] args) {
        SpringApplication.run(MioApplication.class, args);
    }
}
