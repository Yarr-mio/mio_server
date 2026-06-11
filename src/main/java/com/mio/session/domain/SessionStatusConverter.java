package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SessionStatusConverter implements AttributeConverter<SessionStatus, String> {

    @Override
    public String convertToDatabaseColumn(SessionStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public SessionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SessionStatus.fromValue(dbData);
    }
}
