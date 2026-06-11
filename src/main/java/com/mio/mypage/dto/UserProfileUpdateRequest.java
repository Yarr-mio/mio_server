package com.mio.mypage.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;

@Getter
public class UserProfileUpdateRequest {

    private String nickname;
    private String ageRange;
    private boolean ageRangePresent = false;

    @JsonSetter("nickname")
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @JsonSetter("age_range")
    public void setAgeRange(String ageRange) {
        this.ageRange = ageRange;
        this.ageRangePresent = true;
    }

    public boolean isEmpty() {
        return nickname == null && !ageRangePresent;
    }
}
