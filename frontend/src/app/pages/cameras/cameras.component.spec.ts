import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CamerasComponent } from './cameras.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera } from '../../models/blink.model';

const DOOR: BlinkCamera = {
  cameraId: '123', name: 'Haustuer', type: 'doorbell', armed: true,
  battery: 'ok', syncName: 'Zuhause', syncArmed: true,
  lastMotionAt: '2026-08-27T12:00:00', lastMotionClipId: '42'
};

describe('CamerasComponent', () => {
  let fixture: ComponentFixture<CamerasComponent>;
  let component: CamerasComponent;
  let blinkService: jasmine.SpyObj<BlinkService>;

  beforeEach(async () => {
    blinkService = jasmine.createSpyObj('BlinkService',
      ['getCameras', 'setCameraArmed', 'setSystemArmed', 'takeSnapshot', 'getClips',
       'thumbnailUrl', 'clipUrl']);
    blinkService.getCameras.and.returnValue(of([DOOR]));
    blinkService.thumbnailUrl.and.callFake((id, key) => `/api/v1/blink/cameras/${id}/thumbnail?t=${key}`);

    await TestBed.configureTestingModule({
      imports: [CamerasComponent],
      providers: [
        { provide: BlinkService, useValue: blinkService },
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(CamerasComponent);
    component = fixture.componentInstance;
  });

  it('gruppiert die Kameras nach Sync-Modul', () => {
    fixture.detectChanges();
    expect(component.groups.length).toBe(1);
    expect(component.groups[0].syncName).toBe('Zuhause');
    expect(component.groups[0].cameras).toEqual([DOOR]);
  });

  it('meldet nur beim Erstabruf einen Fehler', () => {
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    fixture.detectChanges();
    expect(component.error).toBeTruthy();
  });

  it('ein fehlgeschlagener Refresh behaelt den letzten Stand', () => {
    fixture.detectChanges();
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    component.reload();
    expect(component.groups.length).toBe(1);
    expect(component.error).toBeNull();
  });

  it('nicht angemeldet (400) zeigt den Login-Hinweis', () => {
    blinkService.getCameras.and.returnValue(throwError(() => ({ status: 400 })));
    fixture.detectChanges();
    expect(component.notLoggedIn).toBeTrue();
  });

  it('ein Schnappschuss erneuert den Cache-Buster erst nach der Antwort', () => {
    const snapshot$ = new Subject<Blob>();
    blinkService.takeSnapshot.and.returnValue(snapshot$.asObservable());
    fixture.detectChanges();
    const before = component.cacheKey('123');

    component.takeSnapshot(DOOR);
    expect(component.cacheKey('123')).toBe(before);
    expect(component.isSnapshotBusy('123')).toBeTrue();

    snapshot$.next(new Blob());
    snapshot$.complete();
    expect(component.cacheKey('123')).toBeGreaterThan(before);
    expect(component.isSnapshotBusy('123')).toBeFalse();
  });

  it('schaltet die Kamera und laedt danach neu', () => {
    blinkService.setCameraArmed.and.returnValue(of(void 0));
    fixture.detectChanges();
    component.toggleCamera(DOOR);
    expect(blinkService.setCameraArmed).toHaveBeenCalledWith('123', false);
    expect(blinkService.getCameras).toHaveBeenCalledTimes(2);
  });

  it('zeigt die letzte Bewegung an und spielt ihren Clip bei Klick', () => {
    blinkService.clipUrl.and.callFake((camId, clipId) => `/api/v1/blink/cameras/${camId}/clips/${clipId}`);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const motion = host.querySelector('.last-motion') as HTMLButtonElement;
    expect(motion).not.toBeNull();
    expect(motion.textContent).toContain('Letzte Bewegung');

    motion.click();
    expect(component.playingClipUrl).toBe('/api/v1/blink/cameras/123/clips/42');
  });

  it('ohne bekannte Bewegung fehlt die Zeile wortlos', () => {
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, lastMotionAt: null, lastMotionClipId: null }]));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.last-motion')).toBeNull();
  });

  it('ein Bildfehler blendet den Platzhalter ein, ein Schnappschuss setzt ihn zurueck', () => {
    fixture.detectChanges();
    component.onThumbnailError('123');
    expect(component.hasThumbnailError('123')).toBeTrue();

    const snapshot$ = new Subject<Blob>();
    blinkService.takeSnapshot.and.returnValue(snapshot$.asObservable());
    component.takeSnapshot(DOOR);
    snapshot$.next(new Blob());
    snapshot$.complete();
    expect(component.hasThumbnailError('123')).toBeFalse();
  });
});
