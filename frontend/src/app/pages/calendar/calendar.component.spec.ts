import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { CalendarComponent } from './calendar.component';
import { CalendarEvent, CalendarOccurrence } from '../../models/calendar-event.model';

/** August 2026: fester Testmonat, damit das Raster (und damit from/to) deterministisch ist. */
const VIEW_YEAR = 2026;
const VIEW_MONTH = 8;
const AUGUST_FROM = '2026-07-27';
const AUGUST_TO = '2026-09-06';
const JULY_FROM = '2026-06-29';
const JULY_TO = '2026-08-02';
const SEPTEMBER_FROM = '2026-08-31';
const SEPTEMBER_TO = '2026-10-04';

const SINGLE_OCCURRENCE: CalendarOccurrence = {
  eventId: 1,
  occurrenceDate: '2026-08-10',
  recurrenceDate: null,
  title: 'Zahnarzt',
  notes: null,
  category: 'HEALTH',
  allDay: true,
  startTime: null,
  endTime: null,
  endDate: null,
  recurring: false,
  daysUntil: 5
};

const SINGLE_EVENT: CalendarEvent = {
  id: 1,
  title: 'Zahnarzt',
  notes: null,
  category: 'HEALTH',
  allDay: true,
  startDate: '2026-08-10',
  startTime: null,
  endTime: null,
  endDate: null,
  rrule: null,
  recurring: false
};

const RECURRING_OCCURRENCE: CalendarOccurrence = {
  eventId: 2,
  occurrenceDate: '2026-08-12',
  recurrenceDate: '2026-08-12',
  title: 'Muell',
  notes: null,
  category: 'HOUSEHOLD',
  allDay: true,
  startTime: null,
  endTime: null,
  endDate: null,
  recurring: true,
  daysUntil: 7
};

const RECURRING_EVENT: CalendarEvent = {
  id: 2,
  title: 'Muell',
  notes: null,
  category: 'HOUSEHOLD',
  allDay: true,
  startDate: '2026-01-07',
  startTime: null,
  endTime: null,
  endDate: null,
  rrule: 'FREQ=WEEKLY;BYDAY=WE',
  recurring: true
};

describe('CalendarComponent', () => {
  let fixture: ComponentFixture<CalendarComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CalendarComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(CalendarComponent);
    httpMock = TestBed.inject(HttpTestingController);
    // Fester Monat statt des echten "heute" - macht from/to und die Rasterzellen
    // fuer alle Tests vorhersagbar, unabhaengig vom Tagesdatum im CI.
    fixture.componentInstance.viewYear = VIEW_YEAR;
    fixture.componentInstance.viewMonth = VIEW_MONTH;
  });

  afterEach(() => {
    httpMock.verify();
    jasmine.clock().uninstall();
  });

  function expectOccurrencesRequest(from: string, to: string) {
    return httpMock.expectOne(req =>
      req.url === '/api/v1/calendar/events'
      && req.params.get('from') === from
      && req.params.get('to') === to);
  }

  /** Loest den Initial-Load aus (ngOnInit via erstem detectChanges) und beantwortet ihn. */
  function loadInitial(occurrences: CalendarOccurrence[] = []): void {
    fixture.detectChanges();
    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush(occurrences);
    fixture.detectChanges();
  }

  function dayCell(isoDate: string): HTMLElement {
    return requireElement(`.calendar__day[data-date="${isoDate}"]`);
  }

  /** querySelector, das bei fehlendem Treffer sofort mit einer klaren Meldung scheitert
   *  - Ersatz fuer eine Non-Null-Assertion auf ein moeglicherweise nicht vorhandenes Element. */
  function requireElement<T extends Element = HTMLElement>(selector: string): T {
    const el = (fixture.nativeElement as HTMLElement).querySelector(selector);
    if (!el) {
      throw new Error(`Element "${selector}" nicht gefunden.`);
    }
    return el as T;
  }

  it('laedt das Monatsraster beim Init', () => {
    loadInitial([SINGLE_OCCURRENCE]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Zahnarzt');
  });

  it('Monatsnavigation: der "Voriger Monat"-Klick laedt den vorherigen Zeitraum', () => {
    loadInitial([]);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[aria-label="Voriger Monat"]') as HTMLButtonElement).click();

    expectOccurrencesRequest(JULY_FROM, JULY_TO).flush([]);
    expect(fixture.componentInstance.viewYear).toBe(2026);
    expect(fixture.componentInstance.viewMonth).toBe(7);
  });

  it('Monatsnavigation: der "Naechster Monat"-Klick laedt den folgenden Zeitraum', () => {
    loadInitial([]);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[aria-label="Naechster Monat"]') as HTMLButtonElement).click();

    expectOccurrencesRequest(SEPTEMBER_FROM, SEPTEMBER_TO).flush([]);
    expect(fixture.componentInstance.viewMonth).toBe(9);
  });

  it('"Heute" springt zurueck zum aktuellen Monat', () => {
    loadInitial([]);
    const el = fixture.nativeElement as HTMLElement;

    // Bewusst weit weg vom festen Testmonat navigieren, damit der Ruecksprung
    // nicht zufaellig schon am Ziel ist.
    (el.querySelector('[aria-label="Naechster Monat"]') as HTMLButtonElement).click();
    expectOccurrencesRequest(SEPTEMBER_FROM, SEPTEMBER_TO).flush([]);

    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 7, 15)); // 15. August 2026

    const todayButton = Array.from(el.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === 'Heute') as HTMLButtonElement;
    todayButton.click();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
    expect(fixture.componentInstance.viewYear).toBe(2026);
    expect(fixture.componentInstance.viewMonth).toBe(8);
  });

  it('Termine landen in der richtigen Tageszelle', () => {
    loadInitial([SINGLE_OCCURRENCE]);

    expect(dayCell('2026-08-10').textContent).toContain('Zahnarzt');
    expect(dayCell('2026-08-11').textContent).not.toContain('Zahnarzt');
  });

  it('Klick auf einen Tag oeffnet den Dialog zum Anlegen mit vorbelegtem Datum', () => {
    loadInitial([]);
    dayCell('2026-08-10').click();
    fixture.detectChanges();

    const component = fixture.componentInstance;
    expect(component.dialogOpen).toBeTrue();
    expect(component.editing).toBeNull();
    expect(component.form.startDate).toBe('2026-08-10');

    const dialogTitle = (fixture.nativeElement as HTMLElement)
      .querySelector('.calendar__dialog-title');
    expect(dialogTitle?.textContent).toContain('Neuer Termin');
  });

  /** Klickt den (einzigen) Termin-Chip einer Tageszelle - loest openEdit() aus. */
  function clickChip(isoDate: string): void {
    const chip = dayCell(isoDate).querySelector('.calendar__chip') as HTMLButtonElement;
    chip.click();
  }

  /** Klickt "Loeschen" im geoeffneten Termindialog. */
  function clickDelete(): void {
    requireElement('.calendar__btn--danger')
      .dispatchEvent(new Event('click', { bubbles: true }));
  }

  it('Serientermin: "Loeschen" fragt zuerst nach dem Geltungsbereich', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT);
    fixture.detectChanges();

    clickDelete();
    fixture.detectChanges();

    expect(fixture.componentInstance.scopeQuestion).toBe('delete');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Nur diesen Termin');
    expect(text).toContain('Ganze Serie');
  });

  it('Serientermin loeschen, Scope "Nur diesen Termin": ruft deleteOccurrence mit Serien-Id und recurrenceDate auf', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT);
    fixture.detectChanges();

    clickDelete();
    fixture.detectChanges();
    const [occurrenceButton] = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.calendar__dialog-actions button')
    ).filter(b => b.textContent?.trim() === 'Nur diesen Termin');
    (occurrenceButton as HTMLButtonElement).click();

    const del = httpMock.expectOne('/api/v1/calendar/events/2/occurrences/2026-08-12');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
    expect(fixture.componentInstance.dialogOpen).toBeFalse();
  });

  it('Serientermin loeschen, Scope "Ganze Serie": ruft delete auf der Serien-Id auf', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT);
    fixture.detectChanges();

    clickDelete();
    fixture.detectChanges();
    const [seriesButton] = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.calendar__dialog-actions button')
    ).filter(b => b.textContent?.trim() === 'Ganze Serie');
    (seriesButton as HTMLButtonElement).click();

    const del = httpMock.expectOne('/api/v1/calendar/events/2');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
    expect(fixture.componentInstance.dialogOpen).toBeFalse();
  });

  it('Einzeltermin: "Loeschen" fragt nicht nach dem Geltungsbereich, sondern loescht direkt', () => {
    loadInitial([SINGLE_OCCURRENCE]);
    clickChip('2026-08-10');
    httpMock.expectOne('/api/v1/calendar/events/1').flush(SINGLE_EVENT);
    fixture.detectChanges();

    clickDelete();
    fixture.detectChanges();

    expect(fixture.componentInstance.scopeQuestion).toBeNull();
    const del = httpMock.expectOne('/api/v1/calendar/events/1');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  it('Serientermin aendern, Scope "Nur diesen Termin": ruft updateOccurrence mit Serien-Id und recurrenceDate auf', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const titleInput = el.querySelector('input[name="title"]') as HTMLInputElement;
    titleInput.value = 'Muell (verschoben)';
    titleInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (Array.from(el.querySelectorAll('.calendar__dialog-actions button'))
      .find(b => b.textContent?.trim() === 'Speichern') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.scopeQuestion).toBe('save');
    const [occurrenceButton] = Array.from(el.querySelectorAll('.calendar__dialog-actions button'))
      .filter(b => b.textContent?.trim() === 'Nur diesen Termin');
    (occurrenceButton as HTMLButtonElement).click();

    const put = httpMock.expectOne('/api/v1/calendar/events/2/occurrences/2026-08-12');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.title).toBe('Muell (verschoben)');
    expect(put.request.body.rrule).toBeNull();
    put.flush(RECURRING_OCCURRENCE);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  // --- Regressionstests aus dem Quality-Review (Vorkommen-Datum, ungueltige Wiederholung,
  // hangender Speichern-Zustand) -------------------------------------------------------

  /** Findet einen Button ueber seinen sichtbaren Text - wirft klar, statt undefined zu liefern. */
  function findButton(label: string): HTMLButtonElement {
    const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(b => b.textContent?.trim() === label);
    if (!btn) {
      throw new Error(`Button "${label}" nicht gefunden.`);
    }
    return btn as HTMLButtonElement;
  }

  function setInputValue(selector: string, value: string): void {
    const input = requireElement<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('Speichern mit "Ende: Nach Anzahl" und leerem Zahlenfeld zeigt eine Fehlermeldung im Dialog', () => {
    loadInitial([]);
    dayCell('2026-08-10').click();
    fixture.detectChanges();

    const component = fixture.componentInstance;
    // Genau der Zustand direkt nach der Auswahl "Ende: Nach Anzahl" - das Zahlenfeld ist
    // noch leer (count bleibt null, siehe defaultRecurrence()).
    component.recurrence.freq = 'DAILY';
    component.recurrence.endType = 'COUNT';
    fixture.detectChanges();

    findButton('Speichern').click();
    fixture.detectChanges();

    expect(component.dialogOpen).toBeTrue();
    const errorText = (fixture.nativeElement as HTMLElement)
      .querySelector('.calendar__error')?.textContent ?? '';
    expect(errorText.trim().length).toBeGreaterThan(0);
    // httpMock.verify() im afterEach bestaetigt zusaetzlich, dass gar kein Request feuerte.
  });

  it('"Nur diesen Termin" sendet im Request-Body das Datum des angeklickten Vorkommens, nicht den Serienstart', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT); // startDate '2026-01-07'
    fixture.detectChanges();

    // Vorbelegung: das Datumsfeld zeigt das Vorkommen, nicht den Serienstart.
    expect(fixture.componentInstance.form.startDate).toBe('2026-08-12');

    findButton('Speichern').click();
    fixture.detectChanges();
    findButton('Nur diesen Termin').click();

    const put = httpMock.expectOne('/api/v1/calendar/events/2/occurrences/2026-08-12');
    expect(put.request.body.startDate).toBe('2026-08-12');
    put.flush(RECURRING_OCCURRENCE);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  it('"Ganze Serie" ohne Aenderung am Datumsfeld sendet weiterhin den urspruenglichen Serienstart', () => {
    loadInitial([RECURRING_OCCURRENCE]);
    clickChip('2026-08-12');
    httpMock.expectOne('/api/v1/calendar/events/2').flush(RECURRING_EVENT); // startDate '2026-01-07'
    fixture.detectChanges();

    findButton('Speichern').click();
    fixture.detectChanges();
    findButton('Ganze Serie').click();

    const put = httpMock.expectOne('/api/v1/calendar/events/2');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.startDate).toBe('2026-01-07');
    put.flush(RECURRING_EVENT);
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  it('eine ueber die Oberflaeche zusammengeklickte Wiederholung landet als erwartete RRULE im Request', () => {
    loadInitial([]);
    dayCell('2026-08-10').click();
    fixture.detectChanges();

    setInputValue('input[name="title"]', 'Muell rausbringen');

    const freqSelect = requireElement<HTMLSelectElement>('select[name="freq"]');
    freqSelect.value = 'WEEKLY';
    freqSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const mondayButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.calendar__weekday-btn')
    ).find(b => b.textContent?.trim() === 'Mo') as HTMLButtonElement;
    mondayButton.click();
    fixture.detectChanges();

    findButton('Speichern').click();

    const post = httpMock.expectOne('/api/v1/calendar/events');
    expect(post.request.method).toBe('POST');
    expect(post.request.body.rrule).toBe('FREQ=WEEKLY;BYDAY=MO');
    post.flush({ ...SINGLE_EVENT, id: 42, rrule: 'FREQ=WEEKLY;BYDAY=MO', recurring: true });
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  it('Expertenmodus: eine roh eingegebene RRULE landet unveraendert im Request', () => {
    loadInitial([]);
    dayCell('2026-08-10').click();
    fixture.detectChanges();

    setInputValue('input[name="title"]', 'Sonderregel');
    findButton('Erweitert (RRULE direkt eingeben)').click();
    fixture.detectChanges();

    setInputValue('input[name="rawRrule"]', 'FREQ=MONTHLY;BYDAY=-1FR');
    fixture.detectChanges();

    findButton('Speichern').click();

    const post = httpMock.expectOne('/api/v1/calendar/events');
    expect(post.request.body.rrule).toBe('FREQ=MONTHLY;BYDAY=-1FR');
    post.flush({ ...SINGLE_EVENT, id: 43, rrule: 'FREQ=MONTHLY;BYDAY=-1FR', recurring: true });
    fixture.detectChanges();

    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
  });

  it('Dialog schliessen und neu oeffnen setzt Fehlertext und Expertenmodus zurueck', () => {
    loadInitial([]);
    dayCell('2026-08-10').click();
    fixture.detectChanges();

    const component = fixture.componentInstance;
    component.advancedMode = true;
    component.rawRrule = 'FREQ=DAILY';
    component.dialogError = 'Irgendein vorheriger Fehler';
    fixture.detectChanges();

    findButton('Abbrechen').click();
    fixture.detectChanges();
    expect(component.dialogOpen).toBeFalse();

    dayCell('2026-08-11').click();
    fixture.detectChanges();

    expect(component.dialogOpen).toBeTrue();
    expect(component.dialogError).toBeNull();
    expect(component.advancedMode).toBeFalse();
    expect(component.rawRrule).toBe('');
    const errorEl = (fixture.nativeElement as HTMLElement).querySelector('.calendar__error');
    expect(errorEl).toBeFalsy();
  });

  it('zeigt die "+n weitere"-Anzeige ab dem vierten Termin eines Tages', () => {
    const fourOnSameDay: CalendarOccurrence[] = [1, 2, 3, 4].map(n => ({
      ...SINGLE_OCCURRENCE,
      eventId: 100 + n,
      occurrenceDate: '2026-08-10',
      title: `Termin ${n}`
    }));
    loadInitial(fourOnSameDay);

    expect(dayCell('2026-08-10').textContent).toContain('+1 weitere');
  });

  it('eine veraltete Speichern-Antwort schliesst keinen inzwischen neu geoeffneten Dialog und ueberschreibt dessen Fehler nicht', () => {
    loadInitial([]);
    // Dialog A oeffnen und Speichern anstossen (Request bleibt zunaechst offen).
    dayCell('2026-08-10').click();
    fixture.detectChanges();
    setInputValue('input[name="title"]', 'Termin A');
    findButton('Speichern').click();
    const staleCreate = httpMock.expectOne('/api/v1/calendar/events');

    // Waehrend die Antwort noch aussteht: Dialog A verlassen und Dialog B oeffnen.
    findButton('Abbrechen').click();
    fixture.detectChanges();
    dayCell('2026-08-11').click();
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component.saving).toBeFalse();

    // Jetzt trifft die veraltete Antwort auf A ein.
    staleCreate.flush({ ...SINGLE_EVENT, id: 77 });

    // Dialog B muss weiterhin offen und unveraendert sein - insbesondere darf saving nicht
    // erneut angefasst und der Dialog nicht geschlossen worden sein.
    expect(component.dialogOpen).toBeTrue();
    expect(component.form.startDate).toBe('2026-08-11');

    // Das Raster wird trotzdem aktualisiert (die Mutation war serverseitig erfolgreich).
    expectOccurrencesRequest(AUGUST_FROM, AUGUST_TO).flush([]);
    fixture.detectChanges();

    findButton('Abbrechen').click();
  });
});
