package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MessageRoleConverter implements AttributeConverter<MessageRole, String> {

    @Override
    public String convertToDatabaseColumn(MessageRole attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public MessageRole convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MessageRole.fromValue(dbData);
    }
}
