import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { WasteCollectionTileComponent } from './waste-collection-tile.component';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { WasteCollectionEvent } from '../../models/waste-collection.model';

describe('WasteCollectionTileComponent', () => {
  let fixture: ComponentFixture<WasteCollectionTileComponent>;
  let component: WasteCollectionTileComponent;
  let serviceSpy: jasmine.SpyObj<WasteCollectionService>;

  const event = (daysUntil: number, label: string, date = '2026-07-17'): WasteCollectionEvent =>
    ({ date, label, daysUntil });

  async function setup(events: WasteCollectionEvent[]): Promise<void> {
    serviceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    serviceSpy.getUpcoming.and.returnValue(of(events));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionTileComponent],
      providers: [{ provide: WasteCollectionService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionTileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('rendert keine Kachel, wenn nichts ansteht', async () => {
    await setup([]);
    expect(fixture.nativeElement.querySelector('.waste-tile')).toBeNull();
  });

  it('rendert eine Zeile je Termin', async () => {
    await setup([event(1, 'Biotonne'), event(2, 'Restmuell', '2026-07-18')]);
    expect(fixture.nativeElement.querySelectorAll('.waste-tile__row').length).toBe(2);
  });

  it('hebt die Morgen-Zeile hervor', async () => {
    await setup([event(0, 'Papier', '2026-07-16'), event(1, 'Biotonne')]);
    const rows = fixture.nativeElement.querySelectorAll('.waste-tile__row');
    expect(rows[0].classList).not.toContain('waste-tile__row--tomorrow');
    expect(rows[1].classList).toContain('waste-tile__row--tomorrow');
  });

  it('uebersetzt daysUntil in relative Tageslabels', async () => {
    await setup([]);
    expect(component.relativeDayLabel(event(0, 'x', '2026-07-16'))).toBe('Heute');
    expect(component.relativeDayLabel(event(1, 'x', '2026-07-17'))).toBe('Morgen');
    expect(component.relativeDayLabel(event(2, 'x', '2026-07-18'))).toBe('Übermorgen');
    // 2026-07-20 ist ein Montag
    expect(component.relativeDayLabel(event(4, 'x', '2026-07-20'))).toBe('Montag');
  });

  it('ruft getUpcoming ohne days auf, damit das konfigurierte Fenster gilt', async () => {
    await setup([]);
    expect(serviceSpy.getUpcoming).toHaveBeenCalledWith();
  });

  it('blendet sich bei einem API-Fehler aus, statt zu stoeren', async () => {
    serviceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    serviceSpy.getUpcoming.and.returnValue(throwError(() => new Error('kaputt')));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionTileComponent],
      providers: [{ provide: WasteCollectionService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionTileComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.waste-tile')).toBeNull();
  });

  // Der reine Kalenderrechnung von msUntilNextMidnight() haengt vom echten Systemdatum ab
  // und laesst sich ohne eine injizierte Uhr nicht deterministisch pruefen. Stattdessen wird
  // hier die Verdrahtung geprueft: msUntilNextMidnight() wird auf eine feste, kurze Verzoegerung
  // gestubbt (weit unterhalb des stuendlichen Takts), damit sich der Mitternachts-Timer isoliert
  // von der stuendlichen Aktualisierung beobachten laesst.
  it('loest zusaetzlich zur stuendlichen Aktualisierung einen Refresh kurz nach Mitternacht aus', fakeAsync(async () => {
    serviceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    serviceSpy.getUpcoming.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionTileComponent],
      providers: [{ provide: WasteCollectionService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionTileComponent);
    component = fixture.componentInstance;
    spyOn<any>(component, 'msUntilNextMidnight').and.returnValue(5000);

    fixture.detectChanges(); // ngOnInit
    tick(0);
    expect(serviceSpy.getUpcoming).toHaveBeenCalledTimes(1); // startWith(0)

    tick(4999);
    expect(serviceSpy.getUpcoming).toHaveBeenCalledTimes(1); // Mitternachts-Timer noch nicht faellig

    tick(1);
    expect(serviceSpy.getUpcoming).toHaveBeenCalledTimes(2); // vom Mitternachts-Timer ausgeloest

    discardPeriodicTasks();
  }));
});
