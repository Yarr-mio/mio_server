package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SummaryComponentStatusConverter
        implements AttributeConverter<SummaryComponentStatus, String> {

    @Override
    public String convertToDatabaseColumn(SummaryComponentStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public SummaryComponentStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SummaryComponentStatus.fromValue(dbData);
    }
}
