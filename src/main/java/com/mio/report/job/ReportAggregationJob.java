package com.mio.report.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매주 월요일 03:00 KST 실행.
 * MVP: 실시간 집계 방식이므로 이 Job은 stub.
 * post-MVP: 전주 데이터를 사전 집계해 weekly_reports에 저장하는 배치로 전환.
 */
@Slf4j
@Component
public class ReportAggregationJob {

    @Scheduled(cron = "0 0 3 * * MON", zone = "Asia/Seoul")
    public void run() {
        log.info("[ReportAggregationJob] triggered — pre-generation is post-MVP, skipping.");
    }
}
