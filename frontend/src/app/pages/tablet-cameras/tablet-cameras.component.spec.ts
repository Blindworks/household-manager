import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletCamerasComponent } from './tablet-cameras.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera } from '../../models/blink.model';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';

const DOOR: BlinkCamera = {
  cameraId: '123', name: 'Haustuer', type: 'doorbell', armed: true,
  battery: 'ok', syncName: 'Zuhause', syncArmed: true
};

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

  it('enthaelt KEINE Scharf/Unscharf-Steuerung im Markup', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    // KIOSK-Regel: die Steuerung existiert auf dem Tablet gar nicht erst,
    // nicht nur als 403 - ein Fremder soll den Weg nicht sehen.
    expect(host.querySelector('.arm-toggle')).toBeNull();
    expect(host.textContent).not.toContain('unscharf schalten');
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
