package com.household.manager.communicator;

import com.household.manager.communicator.SpeechToTextClient.SpeechToTextException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SpeechModelInstallerTest {

    @Mock
    private SpeechToTextClient client;

    private CommunicatorProperties properties;
    private SpeechModelInstaller installer;

    @BeforeEach
    void setUp() {
        properties = new CommunicatorProperties();
        properties.getStt().setBaseUrl("http://whisper:8000");
        properties.getStt().setAutoInstallModel(true);
        installer = spy(new SpeechModelInstaller(properties, client));
    }

    @Test
    void wiederholtBisDerDienstBereitIst() {
        doReturn(true).when(installer).pause();
        doThrow(new SpeechToTextException("weg", null)).doThrow(new SpeechToTextException("weg", null))
                .doNothing().when(client).installModel();

        installer.install(5);

        verify(client, times(3)).installModel();
    }

    @Test
    void gibtNachDenVersuchenAufOhneZuWerfen() {
        doReturn(true).when(installer).pause();
        doThrow(new SpeechToTextException("weg", null)).when(client).installModel();

        installer.install(3);

        verify(client, times(3)).installModel();
        verify(installer, times(2)).pause();
    }

    @Test
    void einUnterbrochenerThreadHoertAuf() {
        doReturn(false).when(installer).pause();
        doThrow(new SpeechToTextException("weg", null)).when(client).installModel();

        installer.install(5);

        verify(client, times(1)).installModel();
    }

    @Test
    void ohneSchalterOderOhneDienstPassiertNichts() {
        properties.getStt().setAutoInstallModel(false);
        assertFalse(installer.isActive());
        installer.onStartup();
        installer.requestInstall();

        properties.getStt().setAutoInstallModel(true);
        properties.getStt().setBaseUrl("");
        assertFalse(installer.isActive());
        installer.requestInstall();

        verify(client, never()).installModel();
    }

    @Test
    void mitSchalterUndDienstIstErAktiv() {
        doNothing().when(client).installModel();

        assertTrue(installer.isActive());
        installer.install(1);

        verify(client).installModel();
    }
}
