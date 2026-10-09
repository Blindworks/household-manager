import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { By } from '@angular/platform-browser';
import { TabletHeatingComponent } from './tablet-heating.component';
import { ThermostatService } from '../../services/thermostat.service';
import { ThermostatMock } from '../../services/thermostat-mock';
import { ThermostatTileComponent } from '../../components/thermostat-tile/thermostat-tile.component';
import { WeatherService } from '../../services/weather.service';
import { AppearanceService } from '../../services/appearance.service';
import { WeatherOverview } from '../../models/weather.model';
import { Thermostat, ThermostatHistoryResponse } from '../../models/thermostat.model';

describe('TabletHeatingComponent', () => {
  let fixture: ComponentFixture<TabletHeatingComponent>;
  let component: TabletHeatingComponent;
  let service: jasmine.SpyObj<ThermostatService>;
  let mock: ThermostatMock;
  let list: Thermostat[];

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const tiles = () => fixture.debugElement.queryAll(By.directive(ThermostatTileComponent));
  const click = (selector: string): void => {
    (el().querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  };

  async function create(): Promise<void> {
    mock = new ThermostatMock();
    mock.list().subscribe(initial => (list = initial));

    service = jasmine.createSpyObj('ThermostatService', ['list', 'setTarget', 'setMode', 'bulk', 'history']);
    service.list.and.callFake(() => of(list));
    service.history.and.callFake((id, range) => mock.history(id, range));
    service.setTarget.and.callFake((id, t) => mock.setTarget(id, t));
    service.setMode.and.callFake((id, m) => mock.setMode(id, m));
    service.bulk.and.callFake((ids, t) => mock.bulk(ids, t));

    const weather = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weather.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletHeatingComponent],
      providers: [
        provideRouter([]),
        { provide: ThermostatService, useValue: service },
        { provide: WeatherService, useValue: weather },
        { provide: AppearanceService, useValue: { isLight$: of(false) } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletHeatingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => fixture?.destroy());

  describe('mit Mock-Daten', () => {
    beforeEach(create);

    it('rendert fuenf Kacheln', () => {
      expect(tiles().length).toBe(5);
    });

    it('nutzt bei fuenf Thermostaten drei Spalten', () => {
      const grid = el().querySelector('.tablet-heating__grid') as HTMLElement;
      expect(grid.style.gridTemplateColumns).toContain('repeat(3');
    });

    it('ein targetChange einer Kachel ruft setTarget genau einmal', () => {
      tiles()[0].componentInstance.targetChange.emit(22.5);
      expect(service.setTarget).toHaveBeenCalledOnceWith(1, 22.5);
    });

    it('ein modeChange ruft setMode', () => {
      tiles()[1].componentInstance.modeChange.emit('OFF');
      expect(service.setMode).toHaveBeenCalledOnceWith(2, 'OFF');
    });

    it('ein Schreibfehler zeigt eine Meldung an der Kachel und laedt den Serverwert neu', () => {
      service.setTarget.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 502, error: { message: 'Hub nicht erreichbar.' } })));
      service.list.calls.reset();

      tiles()[0].componentInstance.targetChange.emit(25);
      fixture.detectChanges();

      expect(tiles()[0].nativeElement.textContent).toContain('Hub nicht erreichbar.');
      expect(service.list).toHaveBeenCalled();
    });

    it('"Alle auf 17" oeffnet nur den Dialog, ohne Request vor Bestaetigen', () => {
      click('[data-bulk="17"]');
      expect(el().querySelector('.confirm-dialog')).not.toBeNull();
      expect(service.bulk).not.toHaveBeenCalled();
    });

    it('Abbrechen schliesst den Dialog ohne Request', () => {
      click('[data-bulk="17"]');
      click('.dialog-cancel');
      expect(el().querySelector('.confirm-dialog')).toBeNull();
      expect(service.bulk).not.toHaveBeenCalled();
    });

    it('Bestaetigen schaltet nur die zum Bestaetigungszeitpunkt online befindlichen Thermostate', () => {
      click('[data-bulk="17"]');
      // Zwischen Oeffnen und Bestaetigen geht das Schlafzimmer offline.
      list = list.map(t => (t.id === 2 ? { ...t, online: false } : t));
      component.reload();

      click('.dialog-confirm');

      const [ids, temp] = service.bulk.calls.mostRecent().args;
      expect(temp).toBe(17);
      expect(ids).toEqual([1, 4, 5]);
      expect(el().querySelector('.confirm-dialog')).toBeNull();
    });

    it('Zeitraumwechsel bestellt die laufenden Verlaufsabrufe ab', () => {
      const first = new Subject<ThermostatHistoryResponse>();
      const second = new Subject<ThermostatHistoryResponse>();
      let calls = 0;
      service.history.and.callFake(() => (calls++ === 0 ? first : second));

      component.setRange('DAY');  // gleicher Zeitraum: kein Neuabruf
      component.reload();         // startet Abrufe gegen `first`
      expect(first.observed).toBeTrue();

      component.setRange('WEEK');
      expect(first.observed).toBeFalse();
    });
  });

  describe('Fehlerverhalten', () => {
    it('Fehler beim Erstabruf zeigt eine Meldung', async () => {
      await create();
      service.list.and.returnValue(throwError(() => new Error('x')));
      const fresh = TestBed.createComponent(TabletHeatingComponent);
      fresh.detectChanges();
      expect(fresh.nativeElement.textContent).toContain('Thermostate konnten nicht geladen werden');
      fresh.destroy();
    });

    it('Fehler beim zweiten Abruf behaelt die Kacheln', async () => {
      await create();
      service.list.and.returnValue(throwError(() => new Error('x')));
      component.reload();
      fixture.detectChanges();
      expect(tiles().length).toBe(5);
      expect(el().textContent).not.toContain('konnten nicht geladen werden');
    });
  });

  describe('Hoehenkette', () => {
    beforeEach(create);

    it('gibt den Verlaufsdiagrammen die Bildschirmhoehe statt der Inhaltshoehe', () => {
      // Eigener Container statt des Elternknotens - siehe tablet-network-Spec.
      const host = el();
      const frame = document.createElement('div');
      frame.style.display = 'flex';
      frame.style.flexDirection = 'column';
      document.body.appendChild(frame);
      frame.appendChild(host);

      const chartHeights = (): number[] =>
        Array.from(host.querySelectorAll('.tablet-heating__spark'))
          .map(chart => chart.getBoundingClientRect().height);

      frame.style.height = '900px';
      fixture.detectChanges();
      const small = chartHeights();

      frame.style.height = '1200px';
      fixture.detectChanges();
      const large = chartHeights();

      expect(small.length).toBe(5);
      small.forEach(height => expect(height).toBeGreaterThan(30));
      large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 20));

      document.body.appendChild(host);
      frame.remove();
    });
  });
});
