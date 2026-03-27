package com.siteplain.data.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.any;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class NplBoundaryRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NplBoundaryRepository repository = new NplBoundaryRepository(jdbcTemplate);

    @Test
    void findGeoJsonByIds_emptyList_returnsEmptyMapWithoutQueryingDb() {
        Map<String, String> result = repository.findGeoJsonByIds(List.of());

        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void findGeoJsonByIds_nullList_returnsEmptyMapWithoutQueryingDb() {
        Map<String, String> result = repository.findGeoJsonByIds(null);

        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }
}
