package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MessageKindConverter implements AttributeConverter<MessageKind, String> {

    @Override
    public String convertToDatabaseColumn(MessageKind attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public MessageKind convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MessageKind.fromValue(dbData);
    }
}
