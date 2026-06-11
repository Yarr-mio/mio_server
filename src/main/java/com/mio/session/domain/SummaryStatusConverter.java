package com.mio.session.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SummaryStatusConverter implements AttributeConverter<SummaryStatus, String> {

    @Override
    public String convertToDatabaseColumn(SummaryStatus attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public SummaryStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SummaryStatus.fromValue(dbData);
    }
}
