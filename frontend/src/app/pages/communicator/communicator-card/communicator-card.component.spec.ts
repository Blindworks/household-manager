import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CommunicatorAction, CommunicatorCard } from '../../../models/communicator.model';
import { CommunicatorService } from '../../../services/communicator.service';
import { CommunicatorCardComponent } from './communicator-card.component';

describe('CommunicatorCardComponent', () => {
  let fixture: ComponentFixture<CommunicatorCardComponent>;
  let service: jasmine.SpyObj<CommunicatorService>;
  const toggle: CommunicatorAction = { label: 'Flur einschalten', method: 'POST', path: '/v1/switches/a/toggle' };

  function show(card: Partial<CommunicatorCard>): HTMLElement {
    fixture.componentRef.setInput('card', { title: 'T', icon: 'info', blocks: [], actions: [], questions: [], ...card });
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const button = (selector: string): HTMLButtonElement => fixture.nativeElement.querySelector(selector);

  beforeEach(() => {
    service = jasmine.createSpyObj<CommunicatorService>('CommunicatorService', ['execute']);
    service.execute.and.returnValue(of(undefined));
    TestBed.configureTestingModule({
      imports: [CommunicatorCardComponent],
      providers: [{ provide: CommunicatorService, useValue: service }]
    });
    fixture = TestBed.createComponent(CommunicatorCardComponent);
  });

  it('zeigt Wert, Liste und Verlauf', () => {
    const el = show({
      blocks: [
        { type: 'value', value: '20,4', unit: '°C' },
        { type: 'list', items: [{ title: 'Runde', value: '2,1', unit: 'km' }] },
        { type: 'chart', points: [{ label: 'a', value: 1 }, { label: 'b', value: 3 }] }
      ]
    });
    expect(el.querySelector('.value__main')?.textContent).toContain('20,4');
    expect(el.querySelector('.list__value')?.textContent).toContain('2,1 km');
    expect(el.querySelector('polyline')?.getAttribute('points')).toBe('0,38 100,2');
  });

  it('führt eine Aktion erst auf Tipp und nur einmal aus', () => {
    show({ actions: [toggle] });
    expect(service.execute).not.toHaveBeenCalled();
    button('.action').click();
    fixture.detectChanges();
    expect(service.execute).toHaveBeenCalledOnceWith(toggle, undefined);
    expect(button('.action').disabled).toBeTrue();
  });

  it('lässt eine gescheiterte Aktion wiederholen und zeigt die Meldung des Servers', () => {
    service.execute.and.returnValue(throwError(() => ({ error: { message: 'Keine Berechtigung' } })));
    const el = show({ actions: [toggle] });
    button('.action').click();
    fixture.detectChanges();
    expect(el.querySelector('.card__error')?.textContent).toContain('Keine Berechtigung');
    expect(button('.action').disabled).toBeFalse();
  });

  it('der Regler sendet erst nach Bestätigung und setzt die Zahl in den Body ein', () => {
    const action: CommunicatorAction = { label: 'Setzen', method: 'PUT', path: '/v1/x', body: { t: '$value', n: 'a' } };
    show({ blocks: [{ type: 'slider', number: 21, min: 5, max: 21.5, step: 0.5, action }] });
    expect(button('.action')).toBeNull();
    const plus = fixture.nativeElement.querySelectorAll('.target__step')[1] as HTMLButtonElement;
    plus.click();
    plus.click();
    fixture.detectChanges();
    expect(service.execute).not.toHaveBeenCalled();
    button('.action').click();
    expect(service.execute).toHaveBeenCalledOnceWith(action, { t: 21.5, n: 'a' });
  });

  it('verlangt bei einer heiklen Aktion einen zweiten Tipp und zeigt die gesendeten Werte', () => {
    const open: CommunicatorAction = { label: 'Licht an', method: 'POST', path: '/v1/lock', body: { action: 'UNLATCH' }, confirm: true };
    const el = show({ actions: [open] });
    expect(el.querySelector('.action__target')?.textContent).toContain('{"action":"UNLATCH"}');
    button('.action').click();
    fixture.detectChanges();
    expect(service.execute).not.toHaveBeenCalled();
    expect(button('.action').textContent).toContain('Wirklich?');
    button('.action').click();
    expect(service.execute).toHaveBeenCalledTimes(1);
  });

  it('ein geänderter Reglerwert hebt die Bestätigung auf', () => {
    const action: CommunicatorAction = { label: 'Setzen', method: 'PUT', path: '/v1/x', body: { t: '$value' }, confirm: true };
    const el = show({ blocks: [{ type: 'slider', number: 20, min: 5, max: 30, step: 1, action }] });
    const plus = el.querySelectorAll('.target__step')[1] as HTMLButtonElement;
    plus.click();
    fixture.detectChanges();
    expect(el.querySelector('.action__target')?.textContent).toContain('{"t":21}');
    button('.action').click();
    plus.click();
    fixture.detectChanges();
    button('.action').click();
    expect(service.execute).not.toHaveBeenCalled();
  });

  it('gibt eine Folgefrage weiter', () => {
    const asked: string[] = [];
    fixture.componentInstance.ask.subscribe(question => asked.push(question));
    show({ questions: ['Und gestern?'] });
    button('.suggestions__chip').click();
    expect(asked).toEqual(['Und gestern?']);
  });
});
