package com.mio.mypage.service;

import com.mio.character.dto.CharacterDto;
import com.mio.character.service.CharacterService;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.mypage.dto.UserProfileResponse;
import com.mio.mypage.dto.UserProfileResponse.EmotionDistributionDto;
import com.mio.mypage.dto.UserProfileResponse.PreferredCharacterDto;
import com.mio.mypage.dto.UserProfileResponse.UserStatsDto;
import com.mio.mypage.dto.UserProfileUpdateRequest;
import com.mio.mypage.dto.UserProfileUpdateResponse;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Map<String, String> EMOTION_LABELS = Map.of(
            "happy",    "기쁨",
            "calm",     "평온",
            "anxious",  "불안",
            "sad",      "슬픔",
            "angry",    "화남",
            "ashamed",  "수치",
            "numb",     "무감각",
            "tired",    "피곤",
            "confused", "혼란"
    );

    private final UserRepository userRepository;
    private final CheckinRepository checkinRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final CharacterService characterService;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = findUser(userId);
        LocalDate today = LocalDate.now(KST);

        CharacterDto charInfo = characterService.getCharacterInfo(user.getPreferredCharacterId());
        PreferredCharacterDto preferredCharacter = charInfo != null
                ? new PreferredCharacterDto(charInfo.characterId(), charInfo.name(), charInfo.animal(), charInfo.description())
                : null;

        long totalCheckins = checkinRepository.countByUser_Id(userId);
        int consecutiveDays = calculateConsecutiveDays(userId, today);
        long todoCompleted = behaviorTaskRepository.countByUser_IdAndStatus(userId, TaskStatus.COMPLETED);

        List<EmotionDistributionDto> emotionDist = calculateMonthlyEmotionDistribution(userId, today);

        return new UserProfileResponse(
                user.getId(),
                user.getNickname(),
                user.getAgeRange(),
                preferredCharacter,
                new UserStatsDto(totalCheckins, consecutiveDays, todoCompleted),
                emotionDist,
                user.getSignupStep().name(),
                user.getSignupCompletedAt()
        );
    }

    @Transactional
    public UserProfileUpdateResponse updateProfile(UUID userId, UserProfileUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        String nickname = request.getNickname();
        if (nickname != null) {
            if (nickname.isBlank() || nickname.length() < 2 || nickname.length() > 10) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
                throw new BusinessException(ErrorCode.NICKNAME_DUPLICATE);
            }
        }

        String ageRange = request.getAgeRange();
        if (ageRange != null) {
            Set<String> validAgeRanges = Set.of("10대", "20대", "30대", "40대", "50대+");
            if (!validAgeRanges.contains(ageRange)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }

        User user = findUser(userId);
        user.updateProfile(nickname, ageRange, request.isAgeRangePresent());

        return new UserProfileUpdateResponse(
                user.getId(),
                user.getNickname(),
                user.getAgeRange(),
                user.getUpdatedAt()
        );
    }

    private int calculateConsecutiveDays(UUID userId, LocalDate today) {
        List<LocalDate> dates = checkinRepository.findDistinctCheckinDatesByUserId(userId);
        if (dates.isEmpty()) return 0;

        Set<LocalDate> dateSet = new HashSet<>(dates);
        LocalDate start = dateSet.contains(today) ? today : today.minusDays(1);
        if (!dateSet.contains(start)) return 0;

        int streak = 0;
        LocalDate current = start;
        while (dateSet.contains(current)) {
            streak++;
            current = current.minusDays(1);
        }
        return streak;
    }

    private List<EmotionDistributionDto> calculateMonthlyEmotionDistribution(UUID userId, LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        List<Object[]> rows = checkinRepository.countEmotionsByUserIdSince(userId, monthStart);
        if (rows.isEmpty()) return List.of();

        long total = rows.stream().mapToLong(r -> (Long) r[1]).sum();
        List<Object[]> top3 = rows.size() > 3 ? rows.subList(0, 3) : rows;

        List<int[]> items = new ArrayList<>();
        for (Object[] row : top3) {
            long count = (Long) row[1];
            items.add(new int[]{(int) Math.round(100.0 * count / total)});
        }

        // 반올림 오차 1위에 조정
        int sum = items.stream().mapToInt(a -> a[0]).sum();
        if (sum != 100) {
            items.get(0)[0] += (100 - sum);
        }

        List<EmotionDistributionDto> result = new ArrayList<>();
        for (int i = 0; i < top3.size(); i++) {
            String emotionType = (String) top3.get(i)[0];
            result.add(new EmotionDistributionDto(emotionType, EMOTION_LABELS.getOrDefault(emotionType, emotionType), items.get(i)[0]));
        }
        return result;
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
