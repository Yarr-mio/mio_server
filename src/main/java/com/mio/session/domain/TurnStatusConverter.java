package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TurnStatusConverter implements AttributeConverter<TurnStatus, String> {

    @Override
    public String convertToDatabaseColumn(TurnStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public TurnStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TurnStatus.fromValue(dbData);
    }
}
