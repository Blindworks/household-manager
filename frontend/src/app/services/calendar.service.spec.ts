import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CalendarService } from './calendar.service';
import { CalendarEventRequest, CalendarOccurrence } from '../models/calendar-event.model';

describe('CalendarService', () => {
  let service: CalendarService;
  let httpMock: HttpTestingController;

  /** Die Kategorie, wie das Backend sie in Antworten einbettet. */
  const category = { id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null };

  const request: CalendarEventRequest = {
    title: 'Zahnarzt',
    notes: null,
    categoryId: category.id,
    personUserIds: [],
    allDay: false,
    startDate: '2026-08-03',
    startTime: '14:30',
    endTime: null,
    endDate: null,
    rrule: null
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CalendarService]
    });
    service = TestBed.inject(CalendarService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fragt Vorkommen mit from und to ab', () => {
    service.getOccurrences('2026-07-01', '2026-07-31').subscribe();
    httpMock.expectOne('/api/v1/calendar/events?from=2026-07-01&to=2026-07-31').flush([]);
  });

  it('fragt die naechsten Termine mit Standard-Limit 3 ab', () => {
    service.getUpcoming().subscribe();
    httpMock.expectOne('/api/v1/calendar/upcoming?limit=3').flush([]);
  });

  it('reicht ein uebergebenes Limit durch', () => {
    service.getUpcoming(10).subscribe();
    httpMock.expectOne('/api/v1/calendar/upcoming?limit=10').flush([]);
  });

  it('laedt einen einzelnen Termin', () => {
    service.getEvent(5).subscribe();
    const req = httpMock.expectOne('/api/v1/calendar/events/5');
    expect(req.request.method).toBe('GET');
    req.flush({ ...request, id: 5, recurring: false });
  });

  it('legt einen Termin per POST an', () => {
    service.create(request).subscribe();
    const req = httpMock.expectOne('/api/v1/calendar/events');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush({ ...request, id: 1, recurring: false });
  });

  it('aendert einen Termin per PUT', () => {
    service.update(5, request).subscribe();
    const req = httpMock.expectOne('/api/v1/calendar/events/5');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush({ ...request, id: 5, recurring: false });
  });

  it('loescht einen Termin per DELETE', () => {
    service.delete(5).subscribe();
    const req = httpMock.expectOne('/api/v1/calendar/events/5');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('loescht ein einzelnes Vorkommen (EXDATE) ueber den zusammengesetzten Pfad', () => {
    service.deleteOccurrence(5, '2026-07-25').subscribe();
    const req = httpMock.expectOne('/api/v1/calendar/events/5/occurrences/2026-07-25');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('aendert ein einzelnes Vorkommen (Override) und liefert die Occurrence zurueck', () => {
    const occurrence: CalendarOccurrence = {
      eventId: 5,
      occurrenceDate: '2026-07-25',
      recurrenceDate: '2026-07-25',
      title: request.title,
      notes: request.notes,
      category,
      persons: [],
      allDay: request.allDay,
      startTime: request.startTime,
      endTime: request.endTime,
      endDate: request.endDate,
      recurring: true,
      daysUntil: 0
    };

    service.updateOccurrence(5, '2026-07-25', request)
      .subscribe(result => expect(result).toEqual(occurrence));

    const req = httpMock.expectOne('/api/v1/calendar/events/5/occurrences/2026-07-25');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush(occurrence);
  });

  it('reicht die Fehlermeldung des Backends durch', (done) => {
    service.getEvent(5).subscribe({
      error: (err: Error) => {
        expect(err.message).toBe('Termin nicht gefunden.');
        done();
      }
    });

    httpMock.expectOne('/api/v1/calendar/events/5').flush(
      { message: 'Termin nicht gefunden.' },
      { status: 404, statusText: 'Not Found' }
    );
  });

  it('nutzt einen Fallbacktext, wenn das Backend keine Meldung liefert', (done) => {
    service.getEvent(5).subscribe({
      error: (err: Error) => {
        expect(err.message).toBe('Fehler bei der Kalender-Kommunikation.');
        done();
      }
    });

    httpMock.expectOne('/api/v1/calendar/events/5').flush(
      null,
      { status: 500, statusText: 'Server Error' }
    );
  });
});
