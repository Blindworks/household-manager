import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TabletToniComponent } from './tablet-toni.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { TABLET_VIEWS } from '../../shared/tablet-views';

describe('TabletToniComponent', () => {
  let fixture: ComponentFixture<TabletToniComponent>;
  let petFoodSpy: jasmine.SpyObj<PetFoodService>;
  let tractiveSpy: jasmine.SpyObj<TractiveService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const toni: TractivePet = {
    trackerId: 'dev-9', name: 'Toni', latitude: 48.2, longitude: 16.37,
    batteryPercent: 78, charging: false, zone: 'Zuhause', atHome: true,
    lastSeen: '2026-08-22T14:22:00Z'
  };
  const runde: TractiveWalk = {
    start: '2026-08-22T05:12:00Z', end: '2026-08-22T05:48:00Z',
    durationMinutes: 36, distanceMeters: 2100
  };

  beforeEach(async () => {
    petFoodSpy = jasmine.createSpyObj('PetFoodService', ['getStatus']);
    petFoodSpy.getStatus.and.returnValue(
      of({ cansRemaining: 34, targetCans: 48, percent: 71, daysRemaining: 34 }));

    tractiveSpy = jasmine.createSpyObj('TractiveService', ['getPets', 'getWalks']);
    tractiveSpy.getPets.and.returnValue(of([toni]));
    tractiveSpy.getWalks.and.returnValue(of([runde]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletToniComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: PetFoodService, useValue: petFoodSpy },
        { provide: TractiveService, useValue: tractiveSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletToniComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('steht in der Ansichtsleiste des Tablets', () => {
    // Ohne diesen Eintrag waere die Seite auf dem Tablet nicht erreichbar - im
    // Tablet-Modus blendet die App den Header samt Navigation komplett aus.
    expect(TABLET_VIEWS.some(view => view.route === '/tablet/toni')).toBeTrue();
  });

  it('rahmt sich in die Tablet-Shell mit der Ueberschrift Toni', () => {
    const heading = (fixture.nativeElement as HTMLElement).querySelector('.lumina__heading');
    expect(heading?.textContent?.trim()).toBe('Toni');
  });

  it('zeigt vier Kacheln', () => {
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-toni__card');
    expect(cards.length).toBe(4);
  });

  it('laedt Futtervorrat und Tracker beim Start', () => {
    expect(petFoodSpy.getStatus).toHaveBeenCalledTimes(1);
    expect(tractiveSpy.getPets).toHaveBeenCalledTimes(1);
  });

  it('holt die Spaziergaenge des ersten Tiers mit dem Standardzeitraum', () => {
    expect(tractiveSpy.getWalks).toHaveBeenCalledOnceWith('dev-9', 7);
  });

  it('fragt ohne Tracker gar nicht erst nach Spaziergaengen', () => {
    tractiveSpy.getPets.and.returnValue(of([]));
    tractiveSpy.getWalks.calls.reset();

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();

    expect(tractiveSpy.getWalks).not.toHaveBeenCalled();
    fresh.destroy();
  });

  it('laedt bei einem Zeitraumwechsel genau einmal nach', () => {
    tractiveSpy.getWalks.calls.reset();
    fixture.componentInstance.setWalkDays(30);

    expect(tractiveSpy.getWalks).toHaveBeenCalledOnceWith('dev-9', 30);
    expect(fixture.componentInstance.walkDays).toBe(30);
  });

  it('laedt nicht nach, wenn der aktive Zeitraum erneut gewaehlt wird', () => {
    tractiveSpy.getWalks.calls.reset();
    fixture.componentInstance.setWalkDays(7);

    expect(tractiveSpy.getWalks).not.toHaveBeenCalled();
  });

  it('haelt die Quellen auseinander: ein Tractive-Ausfall laesst das Futter stehen', () => {
    // Bewusst der ERSTabruf und nicht reload(): ein stiller Hintergrund-Refresh
    // setzt absichtlich gar keine Fehlermeldung. Hier geht es darum, dass ein
    // Ausfall der einen Quelle die andere nicht mitreisst.
    tractiveSpy.getPets.and.returnValue(throwError(() => new Error('offline')));

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();
    const component = fresh.componentInstance;

    expect(component.food).not.toBeNull();
    expect(component.petError).not.toBeNull();
    expect(component.foodError).toBeNull();

    fresh.destroy();
  });

  it('behaelt bei einem fehlgeschlagenen Hintergrund-Refresh die bisherigen Werte', () => {
    // Auf einer Wandanzeige sind alte Zahlen mehr wert als eine Fehlermeldung.
    const component = fixture.componentInstance;
    petFoodSpy.getStatus.and.returnValue(throwError(() => new Error('offline')));

    component.reload();

    expect(component.food?.cansRemaining).toBe(34);
    expect(component.foodError).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf des Futters scheitert', () => {
    petFoodSpy.getStatus.and.returnValue(throwError(() => new Error('offline')));

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.foodError).not.toBeNull();
    expect(fresh.componentInstance.food).toBeNull();
    fresh.destroy();
  });

  it('zeigt Dosenzahl und Reichweite des Futtervorrats', () => {
    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-toni__card--food');
    expect(card?.textContent).toContain('34');
    expect(card?.textContent).toContain('34 Tage');
  });

  it('faerbt den Fuellstand nach derselben Regel wie die Seite /pet-food', () => {
    const component = fixture.componentInstance;
    expect(component.foodTone).toBe('ok');

    component.food = { cansRemaining: 6.5, targetCans: 48, percent: 14, daysRemaining: 6 };
    expect(component.foodTone).toBe('critical');
  });

  it('zeigt statt eines leeren Balkens einen Hinweis, wenn der Vorrat fehlt', () => {
    const component = fixture.componentInstance;
    component.food = null;
    component.foodError = 'Futtervorrat nicht verfügbar.';
    fixture.detectChanges();

    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-toni__card--food');
    expect(card?.querySelector('.tablet-toni__hint')?.textContent)
      .toContain('Futtervorrat nicht verfügbar.');
  });

  it('zeigt Zuhause-Badge, Akku und Zeitpunkt des letzten Berichts', () => {
    const card = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status');
    expect(card?.querySelector('.tablet-toni__badge')?.textContent?.trim()).toBe('Zu Hause');
    expect(card?.textContent).toContain('78');
    expect(card?.textContent).toContain('Zuletzt gesehen');
  });

  it('zeigt Unterwegs, wenn der Hund nicht zu Hause ist', () => {
    fixture.componentInstance.pet = { ...toni, atHome: false };
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status .tablet-toni__badge');
    expect(badge?.textContent?.trim()).toBe('Unterwegs');
  });

  it('zeigt gar kein Badge, wenn keine Aussage moeglich ist', () => {
    // atHome fehlt im JSON, wenn kein Zuhause hinterlegt ist oder keine Position
    // vorliegt. Ein geratenes "Zu Hause" waere hier schlimmer als gar nichts.
    const { atHome, ...ohneAussage } = toni;
    fixture.componentInstance.pet = ohneAussage as TractivePet;
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status .tablet-toni__badge');
    expect(badge).toBeNull();
  });

  it('baut je Tag des Zeitraums einen Balken', () => {
    const options = fixture.componentInstance.walkChartOptions as {
      xAxis: { data: string[] };
      series: { data: number[] }[];
    };

    expect(options.xAxis.data.length).toBe(7);
    expect(options.series[0].data.length).toBe(7);
  });

  it('baut die Balken nach einem Zeitraumwechsel neu', () => {
    fixture.componentInstance.setWalkDays(30);

    const options = fixture.componentInstance.walkChartOptions as { xAxis: { data: string[] } };
    expect(options.xAxis.data.length).toBe(30);
  });

  it('zeigt die letzten drei Runden im Klartext', () => {
    const component = fixture.componentInstance;
    expect(component.recentWalks.length).toBe(1);
    expect(component.recentWalks[0].duration).toBe('36 min');
    expect(component.recentWalks[0].distance).toBe('2,1 km');
    expect(component.recentWalks[0].timeRange).toContain('Uhr');
  });

  it('kuerzt die Klartextliste auf drei Runden', () => {
    const component = fixture.componentInstance;
    component.walks = [runde, runde, runde, runde, runde];
    component.rebuildWalkView();

    expect(component.recentWalks.length).toBe(3);
  });

  it('zeigt einen Hinweis, wenn im Zeitraum keine Runde liegt', () => {
    const component = fixture.componentInstance;
    component.walks = [];
    component.rebuildWalkView();
    fixture.detectChanges();

    const card = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--walks');
    expect(card?.textContent).toContain('Keine Runde');
  });

  it('zeigt die neue Achsenlaenge sofort, noch vor der Antwort des Servers', () => {
    // Genau dafuer ruft setWalkDays selbst rebuildWalkView auf. Mit einem Subject,
    // das erst spaeter emittiert, laesst sich das ueberhaupt pruefen - ein of(...)
    // kaeme synchron zurueck und verdeckte den Aufruf.
    const pending = new Subject<TractiveWalk[]>();
    tractiveSpy.getWalks.and.returnValue(pending.asObservable());

    fixture.componentInstance.setWalkDays(14);

    const options = fixture.componentInstance.walkChartOptions as { xAxis: { data: string[] } };
    expect(options.xAxis.data.length).toBe(14);

    pending.complete();
  });
});
