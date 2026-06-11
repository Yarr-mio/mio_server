package com.mio.todo.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TodoTemplateProvider {

    public record TaskTemplate(String actionText, String category, int difficulty, int estimatedMinutes) {}

    private static final Map<String, List<TaskTemplate>> TEMPLATES = Map.of(
            "anxious", List.of(
                    new TaskTemplate("5분 복식 호흡으로 긴장 풀기", "심리_안정", 1, 5),
                    new TaskTemplate("걱정 목록 작성 후 통제 가능/불가능 분류하기", "인지_재구성", 2, 10)
            ),
            "sad", List.of(
                    new TaskTemplate("오늘 감사한 일 3가지 적기", "심리_안정", 1, 5),
                    new TaskTemplate("10분 가벼운 산책하기", "행동_활성화", 1, 10)
            ),
            "angry", List.of(
                    new TaskTemplate("감정 일기에 지금 느끼는 것 자유롭게 쓰기", "심리_안정", 1, 10),
                    new TaskTemplate("분노를 유발한 상황을 다른 시각으로 바라보기", "인지_재구성", 3, 15)
            ),
            "tired", List.of(
                    new TaskTemplate("15분 낮잠 또는 눈 감고 휴식", "심리_안정", 1, 15),
                    new TaskTemplate("오늘 에너지를 소모시킨 것 하나 줄이기", "행동_활성화", 2, 10)
            ),
            "happy", List.of(
                    new TaskTemplate("이 좋은 감정을 이끈 행동 하나 더 하기", "행동_활성화", 2, 20)
            ),
            "calm", List.of(
                    new TaskTemplate("명상 또는 마음챙김 5분 실천", "심리_안정", 1, 5)
            )
    );

    private static final List<TaskTemplate> DEFAULT_TEMPLATES = List.of(
            new TaskTemplate("오늘 자신을 위한 작은 일 한 가지 해보기", "행동_활성화", 1, 10)
    );

    public List<TaskTemplate> getTemplates(String emotionType) {
        return TEMPLATES.getOrDefault(emotionType, DEFAULT_TEMPLATES);
    }
}
