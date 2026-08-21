import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletShellComponent } from './tablet-shell.component';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { TABLET_VIEWS } from '../../shared/tablet-views';

@Component({
  standalone: true,
  imports: [TabletShellComponent],
  template: '<app-tablet-shell heading="Temperaturen"><p class="inhalt">Seiteninhalt</p></app-tablet-shell>'
})
class HostComponent {}

describe('TabletShellComponent', () => {
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const overview = {
    current: { temperature: 18.6, icon: 1 }
  } as unknown as WeatherOverview;

  beforeEach(async () => {
    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(overview));

    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideRouter([]), { provide: WeatherService, useValue: weatherSpy }]
    }).compileComponents();
  });

  it('zeigt Uhrzeit, Wetter, Titel und den projizierten Inhalt', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.lumina__time')?.textContent).toMatch(/^\d{2}:\d{2}$/);
    expect(host.querySelector('.lumina__weather-temp')?.textContent).toContain('19°C');
    expect(host.querySelector('.lumina__heading')?.textContent).toContain('Temperaturen');
    expect(host.querySelector('.inhalt')?.textContent).toContain('Seiteninhalt');

    fixture.destroy();
  });

  it('verlinkt das Dashboard und jede Tablet-Ansicht', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const links: HTMLAnchorElement[] = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__viewbar-btn')
    );
    expect(links.length).toBe(TABLET_VIEWS.length + 1);
    expect(links[0].getAttribute('href')).toBe('/');
    expect(links[1].getAttribute('href')).toBe(TABLET_VIEWS[0].route);

    fixture.destroy();
  });

  it('zeigt bei einem Wetterfehler einen Platzhalter statt einer Temperatur', () => {
    weatherSpy.getOverview.and.returnValue(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.lumina__weather-temp')?.textContent).toContain('--°C');
    expect(host.textContent).toContain('Nicht verfügbar');

    fixture.destroy();
  });
});
