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
  battery: 'ok', syncName: 'Zuhause', syncArmed: true,
  lastMotionAt: '2026-08-27T12:00:00', lastMotionClipId: '42'
};

const CLIP: BlinkClip = { clipId: 'c1', createdAt: '2026-08-01T10:00:00', sizeBytes: null };

describe('TabletCamerasComponent', () => {
  let fixture: ComponentFixture<TabletCamerasComponent>;
  let blinkService: jasmine.SpyObj<BlinkService>;

  beforeEach(async () => {
    blinkService = jasmine.createSpyObj('BlinkService',
      ['getCameras', 'setCameraArmed', 'setSystemArmed', 'takeSnapshot', 'getClips',
       'thumbnailUrl', 'clipUrl']);
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

  it('gruppiert die Kameras nach Sync-Modul', () => {
    fixture.detectChanges();
    expect(fixture.componentInstance.groups.length).toBe(1);
    expect(fixture.componentInstance.groups[0].syncName).toBe('Zuhause');
    expect(fixture.componentInstance.groups[0].cameras).toEqual([DOOR]);
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

  it('zeigt Schalter fuer Kamera und System (Revision: Tablet darf schalten)', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.camera-arm-toggle')).not.toBeNull();
    expect(host.querySelector('.system-arm-toggle')).not.toBeNull();
  });

  it('scharf schalten geht direkt, ohne Dialog', () => {
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, armed: false }]));
    blinkService.setCameraArmed.and.returnValue(of(void 0));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.camera-arm-toggle') as HTMLButtonElement).click();

    expect(blinkService.setCameraArmed).toHaveBeenCalledWith('123', true);
    expect(fixture.componentInstance.pendingDisarm).toBeNull();
  });

  it('unscharf schalten oeffnet erst den Bestaetigungsdialog', () => {
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.camera-arm-toggle') as HTMLButtonElement).click();

    expect(blinkService.setCameraArmed).not.toHaveBeenCalled();
    expect(fixture.componentInstance.pendingDisarm).not.toBeNull();
  });

  it('der Dialog schaltet nur, wenn die Kamera laut aktueller Liste noch scharf ist', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.requestDisarm({ kind: 'camera', id: '123', name: 'Frontdoor' });

    // Hintergrund-Refresh hat die Kamera inzwischen unscharf geliefert:
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, armed: false }]));
    component['load'](true);
    blinkService.setCameraArmed.and.returnValue(of(void 0));

    component.confirmDisarm();

    expect(blinkService.setCameraArmed).not.toHaveBeenCalled();
    expect(component.pendingDisarm).toBeNull();
  });

  it('zeigt die letzte Bewegung an und spielt ihren Clip bei Klick', () => {
    blinkService.clipUrl.and.callFake((camId, clipId) => `/api/v1/blink/cameras/${camId}/clips/${clipId}`);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const motion = host.querySelector('.last-motion') as HTMLButtonElement;
    expect(motion).not.toBeNull();
    expect(motion.textContent).toContain('Letzte Bewegung');

    motion.click();
    expect(fixture.componentInstance.playingClipUrl).toBe('/api/v1/blink/cameras/123/clips/42');
  });

  it('ohne bekannte Bewegung fehlt die Zeile wortlos', () => {
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, lastMotionAt: null, lastMotionClipId: null }]));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.last-motion')).toBeNull();
  });

  it('ein Bildfehler blendet den Platzhalter ein, ein Schnappschuss setzt ihn zurueck', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.onThumbnailError('123');
    expect(component.hasThumbnailError('123')).toBeTrue();

    blinkService.takeSnapshot.and.returnValue(of(new Blob()));
    component.takeSnapshot(DOOR);
    expect(component.hasThumbnailError('123')).toBeFalse();
  });

  it('gerenderte Bedienelemente bleiben auf der Whitelist', () => {
    // REVISION 2026-08-27: Schalter sind jetzt erlaubt (Nutzerentscheidung,
    // Spec blink-bewegung-und-tablet-schalten). Die Whitelist bleibt das
    // Schutzprinzip: jedes UNBEKANNTE Bedienelement laesst den Test scheitern.
    //
    // Nachbesserung (Review): der Suchbereich ist NICHT `.camera-groups` -
    // Bestaetigungsdialog (`.confirm-overlay`) und Clip-Player (`.clip-player`)
    // liegen als Geschwister ausserhalb davon und waeren sonst unbeobachtet
    // geblieben (genau das sicherheitsrelevanteste Element der Seite). Alle
    // drei Container liegen jedoch gemeinsam im projizierten Seiteninhalt
    // `.lumina__content` der tablet-shell - die Ansichtsleiste und die
    // Kopfzeilen-Aktionen der Shell liegen DAVOR/DANACH ausserhalb dieses
    // Divs und bleiben damit bewusst ausgenommen.
    blinkService.getClips.and.returnValue(of([CLIP]));
    blinkService.clipUrl.and.callFake((camId, clipId) => `/api/v1/blink/cameras/${camId}/clips/${clipId}`);
    fixture.detectChanges();
    const component = fixture.componentInstance;

    // Clip-Liste UND Clip-Player oeffnen, damit deren Knoepfe im DOM stehen.
    component.toggleClips(DOOR);
    fixture.detectChanges();
    component.playClip(DOOR, CLIP);
    fixture.detectChanges();

    // UND den Bestaetigungsdialog oeffnen - er wird nur bei gesetztem
    // pendingDisarm gerendert, sonst prueft der Test ins Leere.
    component.requestDisarm({ kind: 'camera', id: DOOR.cameraId, name: DOOR.name });
    fixture.detectChanges();

    const allowed = ['snapshot', 'clips-toggle', 'clip-entry', 'player-close',
                     'camera-arm-toggle', 'system-arm-toggle', 'last-motion',
                     'dialog-cancel', 'dialog-confirm'];
    const content = fixture.nativeElement.querySelector('.lumina__content') as HTMLElement;
    expect(content)
      .withContext('.lumina__content nicht gefunden - hat sich das Shell-Markup geaendert?')
      .not.toBeNull();

    const controls = Array.from(content.querySelectorAll('button, a, input'));
    for (const control of controls) {
      const matches = allowed.some(cls => control.classList.contains(cls));
      expect(matches).withContext(`Unbekanntes Bedienelement: ${control.outerHTML}`).toBeTrue();
    }
    // Belegt, dass wirklich alle drei Container etwas beigesteuert haben -
    // sonst koennte der Test trivial gruen sein, ohne den Dialog/Player je
    // tatsaechlich zu durchsuchen.
    expect(content.querySelector('.player-close')).not.toBeNull();
    expect(content.querySelector('.dialog-confirm')).not.toBeNull();
    expect(controls.length).toBeGreaterThan(0);
  });
});
