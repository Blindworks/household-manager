import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminAgentComponent } from './admin-agent.component';
import {
  AgentNote,
  AgentPolicy,
  AgentProposal,
  AgentRun,
  AgentRunsResponse,
  AgentSummary
} from '../../models/agent.model';

const BASE = '/api/v1/agent';
const SUMMARY_URL = `${BASE}/summary`;
const POLICIES_URL = `${BASE}/policies`;
const NOTES_URL = `${BASE}/notes`;
const PROPOSALS_URL = `${BASE}/proposals?limit=50`;
const RUNS_URL = `${BASE}/runs`;

const SUMMARY: AgentSummary = { pendingProposals: 1, active: true, telegramConfigured: true };
const POLICIES: AgentPolicy[] = [
  { actionClass: 'HEATING', policy: 'PROPOSE' },
  { actionClass: 'SWITCH', policy: 'PROPOSE' },
  { actionClass: 'MODE', policy: 'DIRECT' },
  { actionClass: 'NOTIFY', policy: 'DIRECT' },
  { actionClass: 'LOCK', policy: 'PROPOSE' }
];
const NOTE: AgentNote = {
  id: 3, kind: 'PREFERENCE', text: 'Schlafzimmer kühl', source: 'USER',
  createdAt: '2026-10-10T08:00:00', expiresAt: null
};
const PROPOSAL: AgentProposal = {
  id: 7, actionClass: 'HEATING', target: 'thermostat.1', description: 'Heizung auf 21 Grad',
  reason: 'Kalt im Bad', urgent: false, status: 'PENDING', decidedBy: null, decidedAt: null,
  deliveredAt: null, error: null, createdAt: '2026-10-12T07:30:00'
};
const RUNS: AgentRunsResponse = {
  runs: [], inputTokensToday: 1200, outputTokensToday: 300, dailyTokenBudget: 300000, budgetExhausted: false
};

describe('AdminAgentComponent', () => {
  let fixture: ComponentFixture<AdminAgentComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminAgentComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminAgentComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  async function load(summary: AgentSummary = SUMMARY, proposals: AgentProposal[] = [PROPOSAL],
                      runs: AgentRunsResponse = RUNS): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(SUMMARY_URL).flush(summary);
    httpMock.expectOne(POLICIES_URL).flush(POLICIES);
    httpMock.expectOne(NOTES_URL).flush([NOTE]);
    httpMock.expectOne(PROPOSALS_URL).flush(proposals);
    httpMock.expectOne(RUNS_URL).flush(runs);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('lädt Einstellungen, Freigaben, Notizen, Vorschläge und Läufe beim Start', async () => {
    await load();

    expect(el.querySelectorAll('.admin-agent__policy-row').length).toBe(5);
    expect(el.querySelector('.admin-agent__notes')?.textContent).toContain('Schlafzimmer kühl');
    expect(el.querySelector('.admin-agent__proposals')?.textContent).toContain('Heizung auf 21 Grad');
    expect(el.querySelector('.admin-agent__runs')?.textContent).toContain('1200 Eingabe- / 300 Ausgabe-Tokens');
    expect(el.querySelector('.admin-agent__banner')).toBeNull();
  });

  it('sperrt die NOTIFY-Freigabe', async () => {
    await load();

    const notify = el.querySelector<HTMLSelectElement>('select[name="policy-NOTIFY"]');
    const heating = el.querySelector<HTMLSelectElement>('select[name="policy-HEATING"]');
    expect(notify?.disabled).toBeTrue();
    expect(heating?.disabled).toBeFalse();
  });

  it('speichert eine geänderte Freigabe', async () => {
    await load();

    const heating = el.querySelector<HTMLSelectElement>('select[name="policy-HEATING"]')!;
    heating.value = 'DIRECT';
    heating.dispatchEvent(new Event('change'));

    const req = httpMock.expectOne(`${POLICIES_URL}/HEATING`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ policy: 'DIRECT' });
    req.flush(POLICIES.map(p => p.actionClass === 'HEATING' ? { ...p, policy: 'DIRECT' } : p));
  });

  it('setzt die Freigabe nach einem Speicherfehler zurück', async () => {
    await load();

    const heating = el.querySelector<HTMLSelectElement>('select[name="policy-HEATING"]')!;
    heating.value = 'DIRECT';
    heating.dispatchEvent(new Event('change'));
    httpMock.expectOne(`${POLICIES_URL}/HEATING`).flush({ message: 'nein' }, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(POLICIES_URL).flush(POLICIES);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector<HTMLSelectElement>('select[name="policy-HEATING"]')!.value).toBe('PROPOSE');
    expect(el.querySelector('.admin-agent__policies .admin-agent__error')?.textContent).toContain('nein');
  });

  it('setzt den Aktiv-Schalter nach einem Speicherfehler zurück', async () => {
    await load();

    const box = el.querySelector<HTMLInputElement>('input[name="agent-active"]')!;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    httpMock.expectOne(`${BASE}/settings`).flush({ message: 'nein' }, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(SUMMARY_URL).flush(SUMMARY);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector<HTMLInputElement>('input[name="agent-active"]')!.checked).toBeTrue();
    expect(el.querySelector('.admin-agent__settings .admin-agent__error')?.textContent).toContain('nein');
  });

  it('sendet eine Entscheidung und ersetzt die Zeile', async () => {
    await load();

    const yes = Array.from(el.querySelectorAll<HTMLButtonElement>('.admin-agent__proposals button'))
      .find(b => b.textContent?.trim() === 'Ja')!;
    yes.click();

    const req = httpMock.expectOne(`${BASE}/proposals/7/decide`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVE' });
    req.flush({ ...PROPOSAL, status: 'EXECUTED', decidedBy: 'web' });
    fixture.detectChanges();

    const section = el.querySelector('.admin-agent__proposals')!;
    expect(section.textContent).toContain('Ausgeführt');
    expect(Array.from(section.querySelectorAll('button')).some(b => b.textContent?.trim() === 'Ja')).toBeFalse();
  });

  it('zeigt die 429-Meldung beim manuellen Lauf inline', async () => {
    await load();

    el.querySelector<HTMLButtonElement>('.admin-agent__run-button')!.click();
    httpMock.expectOne(RUNS_URL).flush({ message: 'zu frisch' }, { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-agent__run-message')?.textContent).toContain('Bitte warten');
    expect(el.querySelector<HTMLButtonElement>('.admin-agent__run-button')?.disabled).toBeFalse();
  });

  it('stellt einen erfolgreichen Lauf voran und zeigt seine Zusammenfassung', async () => {
    await load();

    el.querySelector<HTMLButtonElement>('.admin-agent__run-button')!.click();
    const run: AgentRun = {
      id: null, triggerType: 'MANUAL', triggerDetail: null, startedAt: '2026-10-12T09:00:00',
      durationMs: 2500, model: null, inputTokens: 10, outputTokens: 5, toolCalls: 1,
      summary: 'Alles ruhig', error: null
    };
    httpMock.expectOne(RUNS_URL).flush(run);
    fixture.detectChanges();

    expect(el.querySelector('.admin-agent__run-message')?.textContent).toContain('Alles ruhig');
    expect(el.querySelectorAll('.admin-agent__runs tbody tr').length).toBe(1);
  });

  it('zeigt das Budget in der Kopfzeile der Läufe', async () => {
    await load();

    expect(el.querySelector('.admin-agent__runs')?.textContent).toContain('von 300000');
  });

  it('zeigt das Budget-Banner, wenn das Tagesbudget erschöpft ist', async () => {
    await load(SUMMARY, [PROPOSAL], { ...RUNS, budgetExhausted: true });

    expect(el.querySelector('.admin-agent__runs .admin-agent__banner')?.textContent)
      .toContain('Tagesbudget erreicht');
  });

  it('zeigt „kein Limit“, wenn das Budget 0 ist', async () => {
    await load(SUMMARY, [PROPOSAL], { ...RUNS, dailyTokenBudget: 0 });

    expect(el.querySelector('.admin-agent__runs')?.textContent).toContain('kein Limit');
  });

  it('zeigt das Telegram-Banner, wenn nicht konfiguriert', async () => {
    await load({ ...SUMMARY, telegramConfigured: false });

    expect(el.querySelector('.admin-agent__banner')).not.toBeNull();
  });

  it('legt eine Notiz mit Ablauf im ISO-Format an', async () => {
    await load();

    const comp = fixture.componentInstance;
    comp.newNote.text = 'Hund mag keine Heizung';
    comp.newNote.kind = 'OBSERVATION';
    comp.newNote.expiresAt = '2026-11-01T12:30';
    comp.createNote();

    const req = httpMock.expectOne(NOTES_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      kind: 'OBSERVATION', text: 'Hund mag keine Heizung', expiresAt: '2026-11-01T12:30:00'
    });
    req.flush({ ...NOTE, id: 4 });
    fixture.detectChanges();
    expect(el.querySelectorAll('.admin-agent__note').length).toBe(2);
  });

  it('zeigt den Fehler nur im betroffenen Abschnitt', async () => {
    fixture.detectChanges();
    httpMock.expectOne(SUMMARY_URL).flush(SUMMARY);
    httpMock.expectOne(POLICIES_URL).flush(POLICIES);
    httpMock.expectOne(NOTES_URL).flush({ message: 'kaputt' }, { status: 500, statusText: 'Server Error' });
    httpMock.expectOne(PROPOSALS_URL).flush([PROPOSAL]);
    httpMock.expectOne(RUNS_URL).flush(RUNS);
    fixture.detectChanges();

    expect(el.querySelector('.admin-agent__notes .admin-agent__error')?.textContent).toContain('kaputt');
    expect(el.querySelectorAll('.admin-agent__policy-row').length).toBe(5);
    expect(el.querySelector('.admin-agent__proposals')?.textContent).toContain('Heizung auf 21 Grad');
  });
});
