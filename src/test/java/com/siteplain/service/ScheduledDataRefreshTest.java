package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ScheduledDataRefreshTest {

    @Test
    void beforeTheStartupRefreshHasFinished_skipsWithoutTouchingTheData() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        SitemapService sitemap = mock(SitemapService.class);

        new ScheduledDataRefresh(bootstrap, sitemap, availability(ReadinessState.REFUSING_TRAFFIC)).refresh();

        verify(bootstrap, never()).refresh(anyBoolean());
        verify(sitemap, never()).rebuild();
    }

    @Test
    void onceReady_runsTheNonStartupRefresh_thenRebuildsTheSitemap() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        SitemapService sitemap = mock(SitemapService.class);

        new ScheduledDataRefresh(bootstrap, sitemap, availability(ReadinessState.ACCEPTING_TRAFFIC)).refresh();

        // The sitemap is rebuilt even when nothing changed here: another instance may have swapped data in.
        InOrder order = inOrder(bootstrap, sitemap);
        order.verify(bootstrap).refresh(false);
        order.verify(sitemap).rebuild();
        verify(bootstrap, never()).refresh(true);
    }

    @Test
    void anUnexpectedError_isLoggedAndDoesNotEscapeToTheScheduler() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        SitemapService sitemap = mock(SitemapService.class);
        doThrow(new IllegalStateException("database unreachable")).when(bootstrap).refresh(false);
        doThrow(new IllegalStateException("database unreachable")).when(sitemap).rebuild();

        assertThatCode(() -> new ScheduledDataRefresh(bootstrap, sitemap, availability(ReadinessState.ACCEPTING_TRAFFIC)).refresh())
                .doesNotThrowAnyException();
        verify(sitemap).rebuild();
    }

    @Test
    void scheduledFromTheCronProperty_andAbsentWhenBootstrapIsDisabled() throws Exception {
        DataBootstrapService bootstrap = mock(DataBootstrapService.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ScheduledDataRefresh.class)
                .withBean(DataBootstrapService.class, () -> bootstrap)
                .withBean(SitemapService.class, () -> mock(SitemapService.class))
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
                .withBean(SitemapService.class, () -> mock(SitemapService.class))
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
