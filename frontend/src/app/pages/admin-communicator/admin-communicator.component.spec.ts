import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { CommunicatorHealth, CommunicatorRequestDetail, CommunicatorRequestRow } from '../../models/communicator-log.model';
import { CommunicatorLogService } from '../../services/communicator-log.service';
import { cacheShare, cardLines, formatMs, requestTone, whisperState } from '../../shared/communicator-log-view.util';
import { AdminCommunicatorComponent } from './admin-communicator.component';

const row: CommunicatorRequestRow = {
  id: 1, askedAt: '2026-11-25T09:00:00', actor: 'a', voice: true, status: 'OK', question: 'Wie warm?', error: null,
  model: 'm', audioBytes: 2048, audioType: 'audio/mp4', audioMs: 2000, speechMs: 900, totalMs: 5200, aiCalls: 2,
  toolCalls: 2, toolFailures: 0, inputTokens: 10, outputTokens: 20, cacheReadTokens: 900, cacheWriteTokens: 90,
  problem: false
};
const detail: CommunicatorRequestDetail = {
  row,
  details: {
    steps: [
      { round: 1, type: 'CLAUDE', name: 'tool_use', ok: true, ms: 1500, inputTokens: 10, outputTokens: 20 },
      { round: 1, type: 'TOOL', name: 'show_card', ok: false, ms: 1, input: '{}', output: 'falscher Zeiger' }
    ],
    cards: [{
      title: 'Wohnzimmer', icon: 'thermostat', blocks: [{ type: 'value', label: 'Jetzt', value: '21', unit: '°C' }],
      actions: [{ label: 'Heizen', method: 'PUT', path: '/v1/x', confirm: false }], questions: []
    }]
  }
};
const speech: CommunicatorHealth['speech'] = {
  configured: true, model: 'small', language: 'de', reachable: true, modelInstalled: true, responseMs: 12, error: null,
  installing: false, installError: null
};

describe('AdminCommunicatorComponent', () => {
  let service: jasmine.SpyObj<CommunicatorLogService>;
  let fixture: ComponentFixture<AdminCommunicatorComponent>;

  beforeEach(() => {
    service = jasmine.createSpyObj('CommunicatorLogService', ['getSummary', 'getRequests', 'getRequest', 'getHealth']);
    service.getHealth.and.returnValue(of({
      enabled: true, aiConfigured: true, aiModel: 'm', endpoints: 312, catalogChars: 27000, speech
    }));
    service.getSummary.and.returnValue(of({
      requests: 1, problems: 0, byStatus: { OK: 1 }, tokens: { input: 10, output: 20, cacheRead: 900, cacheWrite: 90 },
      medianTotalMs: 5200, maxTotalMs: 5200, averageAiCalls: 2, toolCalls: 2, toolFailures: 0,
      speech: { recordings: 1, notUnderstood: 0, failures: 0, medianMs: 900, maxMs: 900, realtimeFactor: 0.45 },
      days: [], endpoints: [{ name: '/v1/thermostats', count: 3 }], errors: []
    }));
    service.getRequests.and.returnValue(of({ content: [row], totalElements: 1, page: 0, size: 30 }));
    service.getRequest.and.returnValue(of(detail));
    TestBed.configureTestingModule({ providers: [{ provide: CommunicatorLogService, useValue: service }] });
    fixture = TestBed.createComponent(AdminCommunicatorComponent);
    fixture.detectChanges();
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('zeigt Zustand, Tokens und Anfragen', () => {
    expect(text()).toContain('Bereit');
    expect(text()).toContain('1.020');
    expect(text()).toContain('Wie warm?');
    expect(text()).toContain('/v1/thermostats');
  });

  it('zeigt den Verlauf mit abgelehntem Werkzeug und der Antwort, ohne bedienbare Aktion', () => {
    fixture.nativeElement.querySelector('.admin-communicator__row').click();
    fixture.detectChanges();
    expect(text()).toContain('falscher Zeiger');
    expect(text()).toContain('Jetzt: 21 °C');
    expect(text()).toContain('Knopf „Heizen“ (PUT /v1/x)');
    expect(fixture.nativeElement.querySelector('.admin-communicator__detail button')).toBeNull();
  });

  it('verwirft einen Verlauf, der nach dem Zuklappen eintrifft', () => {
    const late = new Subject<CommunicatorRequestDetail>();
    service.getRequest.and.returnValue(late);
    fixture.componentInstance.toggle(row);
    fixture.componentInstance.toggle(row);
    late.next(detail);
    expect(fixture.componentInstance.detail).toBeNull();
  });

  it('meldet Ladefehler', () => {
    service.getRequests.and.returnValue(throwError(() => new Error('x')));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(text()).toContain('Die Anfragen konnten nicht geladen werden.');
  });

  it('bewertet Zustände und Zahlen', () => {
    expect(whisperState({ ...speech, reachable: false }).tone).toBe('error');
    expect(whisperState({ ...speech, modelInstalled: false, installing: true }).tone).toBe('warn');
    expect(requestTone({ ...row, problem: true })).toBe('warn');
    expect(requestTone({ ...row, status: 'AI_ERROR' })).toBe('error');
    expect(cacheShare({ input: 10, output: 5, cacheRead: 90, cacheWrite: 0 })).toBe(90);
    expect(formatMs(4200)).toBe('4,2 s');
    expect(formatMs(null)).toBe('–');
    expect(cardLines(detail.details!.cards[0]).length).toBe(2);
  });
});
