package com.household.manager.controller;

import com.household.manager.dto.TabletPresenceRequest;
import com.household.manager.tablet.TabletPresenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TabletPresenceControllerTest {

    @Mock
    private TabletPresenceService tabletPresenceService;

    @Test
    void delegatesPresenceReportToService() {
        TabletPresenceController controller = new TabletPresenceController(tabletPresenceService);

        controller.reportPresence("wandtablet", new TabletPresenceRequest(true));

        verify(tabletPresenceService).reportPresence("wandtablet", true);
    }
}
