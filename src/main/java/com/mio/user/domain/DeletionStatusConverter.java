package com.mio.user.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeletionStatusConverter implements AttributeConverter<DeletionStatus, String> {

    @Override
    public String convertToDatabaseColumn(DeletionStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public DeletionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : DeletionStatus.fromValue(dbData);
    }
}
