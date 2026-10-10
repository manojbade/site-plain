package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ScheduledDataRefreshTest {

    @Test
    void beforeTheStartupRefreshHasFinished_skipsWithoutTouchingTheData() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);

        new ScheduledDataRefresh(bootstrap, availability(ReadinessState.REFUSING_TRAFFIC)).refresh();

        verify(bootstrap, never()).refresh(anyBoolean());
    }

    @Test
    void onceReady_runsTheNonStartupRefresh() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);

        new ScheduledDataRefresh(bootstrap, availability(ReadinessState.ACCEPTING_TRAFFIC)).refresh();

        verify(bootstrap).refresh(false);
        verify(bootstrap, never()).refresh(true);
    }

    @Test
    void anUnexpectedError_isLoggedAndDoesNotEscapeToTheScheduler() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        doThrow(new IllegalStateException("database unreachable")).when(bootstrap).refresh(false);

        assertThatCode(() -> new ScheduledDataRefresh(bootstrap, availability(ReadinessState.ACCEPTING_TRAFFIC)).refresh())
                .doesNotThrowAnyException();
    }

    @Test
    void scheduledFromTheCronProperty_andAbsentWhenBootstrapIsDisabled() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ScheduledDataRefresh.class)
                .withBean(DataBootstrapService.class, () -> bootstrap)
                .withBean(ApplicationAvailability.class, () -> availability(ReadinessState.ACCEPTING_TRAFFIC))
                .withPropertyValues("siteplain.data.scheduledRefreshCron=* * * * * *");

        runner.run(context -> {
            assertThat(context).hasSingleBean(ScheduledDataRefresh.class);
            verify(bootstrap, timeout(3000).atLeast(1)).refresh(false);
        });

        DataBootstrapService disabledBootstrap = mock(DataBootstrapService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(ScheduledDataRefresh.class)
                .withBean(DataBootstrapService.class, () -> disabledBootstrap)
                .withBean(ApplicationAvailability.class, () -> availability(ReadinessState.ACCEPTING_TRAFFIC))
                .withPropertyValues("siteplain.data.bootstrapEnabled=false", "siteplain.data.scheduledRefreshCron=* * * * * *")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ScheduledDataRefresh.class);
                    Thread.sleep(1500);
                    verify(disabledBootstrap, never()).refresh(anyBoolean());
                });
    }

    private static ApplicationAvailability availability(ReadinessState state) {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(state);
        return availability;
    }
}
