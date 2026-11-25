import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { BlaulichtEvent } from '../../models/blaulicht.model';
import { BlaulichtService } from '../../services/blaulicht.service';
import { BlaulichtDialogComponent } from './blaulicht-dialog.component';

function event(id: number, overrides: Partial<BlaulichtEvent> = {}): BlaulichtEvent {
  return {
    id, source: 'PRESSEPORTAL', category: 'EINBRUCH', locality: 'Dortelweil', nearby: false,
    title: 'Einbruch in Wohnhaus', summary: 'Text', occurredAt: '2026-11-24T21:50', occurredUntil: null,
    publishedAt: '2026-11-25T14:12:00', url: 'https://www.presseportal.de/blaulicht/pm/154582/1', relevance: 115,
    newSinceEvening: true, ...overrides
  };
}

describe('BlaulichtDialogComponent', () => {
  let fixture: ComponentFixture<BlaulichtDialogComponent>;
  let service: jasmine.SpyObj<BlaulichtService>;

  function create(showLinks = true): HTMLElement {
    fixture = TestBed.createComponent(BlaulichtDialogComponent);
    fixture.componentInstance.showLinks = showLinks;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<BlaulichtService>('BlaulichtService', ['getEvents']);
    service.getEvents.and.returnValue(of([
      event(1),
      event(2, { category: 'UNFALL', locality: 'Bad Vilbel', newSinceEvening: false, url: null }),
      event(3, { category: 'BRAND', locality: 'Karben', nearby: true, newSinceEvening: false })
    ]));
    TestBed.configureTestingModule({
      imports: [BlaulichtDialogComponent],
      providers: [{ provide: BlaulichtService, useValue: service }]
    });
  });

  it('listet Meldungen mit Badge und Link', () => {
    const el = create();
    expect(el.querySelectorAll('[data-test="blaulicht-item"]').length).toBe(3);
    expect(el.querySelectorAll('[data-test="blaulicht-new"]').length).toBe(1);
    expect(el.querySelectorAll('a.bl-link').length).toBe(2);
  });

  it('filtert nach Kategorie und hebt den Filter beim zweiten Tipp auf', () => {
    const el = create();
    const unfall = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-test="blaulicht-filter"]'))
      .find(b => b.textContent!.includes('Unfall'))!;
    unfall.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-test="blaulicht-item"]').length).toBe(1);
    unfall.click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-test="blaulicht-item"]').length).toBe(3);
  });

  it('zeigt in der Tablet-Ansicht keine Links', () => {
    const el = create(false);
    expect(el.querySelectorAll('a.bl-link').length).toBe(0);
  });

  it('meldet einen Ladefehler mit Wiederholen', () => {
    service.getEvents.and.returnValue(throwError(() => new Error('x')));
    const el = create();
    expect(el.textContent).toContain('Meldungen nicht abrufbar');
  });

  it('schliesst beim Tipp auf den Hintergrund', () => {
    const el = create();
    let closed = false;
    fixture.componentInstance.closed.subscribe(() => (closed = true));
    (el.querySelector('.bl-backdrop') as HTMLElement).click();
    expect(closed).toBeTrue();
  });
});
