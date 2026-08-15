package com.mio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

/**
 * AWS CloudWatch 클라이언트 (이슈 #437).
 *
 * <p>인프라 비용 배치 캐싱({@code InfraCostSyncJob})이 {@code AWS/Billing} 네임스페이스의
 * {@code EstimatedCharges} 지표를 읽는 데 쓴다. 처음엔 Cost Explorer({@code ce:GetCostAndUsage})로
 * 구현했으나, 그쪽은 호출 1건당 $0.01가 과금돼(하루 1번 배치라도 월 $0.30) CloudWatch 지표 조회
 * (사실상 무료)로 전환했다 — 리뷰 반영. {@code AWS/Billing} 지표는 리전에 상관없이
 * <b>us-east-1에만 게시</b>된다 — 앱 자체 리전(ap-northeast-2)과 무관한 AWS 고정 사양이다.
 * 크리덴셜은 지정하지 않고 기본 체인을 쓴다 — EC2 인스턴스 역할(`mio-ec2-cloudwatch-role`,
 * `cloudwatch:GetMetricStatistics` 인라인 정책 부여됨)을 그대로 탄다.
 */
@Configuration
public class AwsConfig {

    @Bean
    public CloudWatchClient cloudWatchBillingClient() {
        return CloudWatchClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}
