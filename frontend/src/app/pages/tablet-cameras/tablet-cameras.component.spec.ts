import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletCamerasComponent } from './tablet-cameras.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';

const DOOR: BlinkCamera = {
  cameraId: '123', name: 'Haustuer', type: 'doorbell', armed: true,
  battery: 'ok', syncName: 'Zuhause', syncArmed: true
};

const CLIP: BlinkClip = { clipId: 'c1', createdAt: '2026-08-01T10:00:00', sizeBytes: null };

describe('TabletCamerasComponent', () => {
  let fixture: ComponentFixture<TabletCamerasComponent>;
  let blinkService: jasmine.SpyObj<BlinkService>;

  beforeEach(async () => {
    blinkService = jasmine.createSpyObj('BlinkService',
      ['getCameras', 'takeSnapshot', 'getClips', 'thumbnailUrl', 'clipUrl']);
    blinkService.getCameras.and.returnValue(of([DOOR]));
    blinkService.thumbnailUrl.and.callFake((id, key) => `/api/v1/blink/cameras/${id}/thumbnail?t=${key}`);

    const weatherService = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherService.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletCamerasComponent],
      providers: [
        // app-tablet-shell nutzt routerLink fuer die Ansichtsleiste und das
        // Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: BlinkService, useValue: blinkService },
        { provide: WeatherService, useValue: weatherService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletCamerasComponent);
  });

  it('zeigt die Kameras mit Scharf-Badge', () => {
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Haustuer');
    expect(text).toContain('Scharf');
  });

  it('erlaubt in der Kamera-Ansicht nur die vier bekannten Bedienelemente (Whitelist statt Blacklist)', () => {
    // Eine Blacklist ("kein .arm-toggle", "kein Text 'unscharf schalten'")
    // prueft nur die eine Auspraegung, die es heute nicht gibt - ein kuenftiger
    // Schalter mit anderem Klassennamen oder anderer Beschriftung (oder ein
    // reines Icon ohne Text) liefe unbemerkt durch. Diese KIOSK-Ansicht ist die
    // einzige Barriere gegen Scharf/Unscharf, solange die serverseitige
    // Sperre fuer die Rolle noch aussteht - deshalb hier eine Whitelist: JEDES
    // Bedienelement muss einer der vier erlaubten Kategorien angehoeren
    // (Schnappschuss, Clips-Umschalter, ein Clip-Eintrag, Player-Schliessen).
    // Ein unbekanntes fuenftes Element laesst den Test scheitern, unabhaengig
    // davon, wie es heisst oder beschriftet ist.
    blinkService.getClips.and.returnValue(of([CLIP]));
    fixture.detectChanges();

    const component = fixture.componentInstance;
    // Clip-Liste UND Clip-Player oeffnen, damit auch deren Knoepfe im DOM
    // stehen - sonst waeren sie von der Whitelist nie erfasst.
    component.toggleClips(DOOR);
    fixture.detectChanges();
    component.playClip(DOOR, CLIP);
    fixture.detectChanges();

    // Nur der projizierte Seiteninhalt (.lumina__content), NICHT die
    // Ansichtsleiste der tablet-shell - die bringt eigene Navigations-Knoepfe
    // mit, die nichts mit dieser Ansicht zu tun haben.
    const content = (fixture.nativeElement as HTMLElement).querySelector('.lumina__content');
    expect(content)
      .withContext('.lumina__content nicht gefunden - hat sich das Shell-Markup geaendert?')
      .not.toBeNull();

    const interactive = Array.from(content!.querySelectorAll('button, a, input'));
    expect(interactive.length).toBeGreaterThan(0);

    interactive.forEach(element => {
      const isSnapshot = element.classList.contains('snapshot');
      const isClipsToggle = element.classList.contains('clips-toggle');
      const isClipEntry = element.closest('.clip-list') !== null;
      const isPlayerClose = element.closest('.clip-player') !== null;
      expect(isSnapshot || isClipsToggle || isClipEntry || isPlayerClose)
        .withContext(`Unerwartetes Bedienelement in der Kamera-Ansicht: ${element.outerHTML}`)
        .toBeTrue();
    });
  });

  it('hat einen Schnappschuss-Knopf', () => {
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.snapshot')).not.toBeNull();
  });

  it('nur der Erstabruf meldet einen Fehler', () => {
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    fixture.detectChanges();
    expect(fixture.componentInstance.error).toBeTruthy();
  });
});
