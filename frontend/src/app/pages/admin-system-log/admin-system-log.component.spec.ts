import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { AdminSystemLogComponent } from './admin-system-log.component';
import { SystemLogEntry, SystemLogJob, SystemLogPage } from '../../models/system-log.model';

function entry(partial: Partial<SystemLogEntry>): SystemLogEntry {
  return {
    id: 1, occurredAt: '2026-10-22T10:00:00', kind: 'FLOW_RUN', status: 'OK', source: 'flow:1',
    title: 'Testflow', message: 'ok', details: null, flowId: 1, runId: null, repeatCount: 1, ...partial
  };
}

function page(content: SystemLogEntry[], pageNo = 0, total = content.length): SystemLogPage {
  return { content, totalElements: total, page: pageNo, size: 50 };
}

const failingJob: SystemLogJob = {
  jobKey: 'TractivePollingService.poll', state: 'FAILING', lastRunAt: '2026-10-22T10:00:00',
  lastSuccessAt: '2026-10-22T08:00:00', lastFailureAt: '2026-10-22T10:00:00',
  failingSince: new Date(Date.now() - 2 * 3_600_000).toISOString().substring(0, 19), lastMessage: 'Cloud nicht erreichbar'
};
const okJob: SystemLogJob = {
  jobKey: 'NukiPollingService.poll', state: 'OK', lastRunAt: '2026-10-22T10:00:00',
  lastSuccessAt: '2026-10-22T10:00:00', lastFailureAt: null, failingSince: null, lastMessage: null
};

describe('AdminSystemLogComponent', () => {
  let fixture: ComponentFixture<AdminSystemLogComponent>;
  let http: HttpTestingController;

  const entriesRequests = () => http.match(r => r.url === '/api/v1/admin/system-log/entries');

  function flush(entries: SystemLogEntry[] = [], jobs: SystemLogJob[] = [failingJob, okJob]): void {
    http.expectOne(r => r.url === '/api/v1/admin/system-log/jobs').flush(jobs);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/flows').flush([{ id: 1, name: 'Testflow' }]);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/entries').flush(page(entries));
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AdminSystemLogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    fixture = TestBed.createComponent(AdminSystemLogComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('renders failing job first with since text', () => {
    flush();
    const failing = fixture.nativeElement.querySelector('.admin-system-log__job--failing') as HTMLElement;
    expect(failing).toBeTruthy();
    expect(failing.textContent).toContain('TractivePollingService.poll');
    expect(failing.textContent).toContain('gestört seit');
    expect(failing.textContent).toContain('Cloud nicht erreichbar');
    expect(fixture.nativeElement.textContent).toContain('ok (1)');
  });

  it('problemsOnly toggle sends problemsOnly=true', () => {
    flush();
    const toggle = fixture.nativeElement.querySelector('input[data-test="problems-only"]') as HTMLInputElement;
    toggle.click();
    fixture.detectChanges();
    http.expectOne(r => r.url.endsWith('/flows')).flush([]);
    const req = entriesRequests();
    expect(req.length).toBe(1);
    expect(req[0].request.params.get('problemsOnly')).toBe('true');
    req[0].flush(page([]));
  });

  it('expanding flow run shows steps from details', () => {
    flush([entry({
      details: [
        { nodeId: 'n1', type: 'state-trigger', label: 'Tuer offen', outcome: 'port:0', error: null },
        { nodeId: 'n2', type: 'telegram-send', label: 'Melden', outcome: 'failed', error: 'Timeout' }
      ]
    })]);
    (fixture.nativeElement.querySelector('.admin-system-log__row') as HTMLElement).click();
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Tuer offen');
    expect(text).toContain('Melden');
    expect(text).toContain('Timeout');
  });

  it('expanding run with runId loads /runs/{runId}', () => {
    flush([entry({ runId: 'run-1', details: [{ nodeId: 'n1', type: 'a', label: 'Start', outcome: 'deferred', error: null }] }),
           entry({ id: 2, runId: 'run-1', details: [] })]);
    (fixture.nativeElement.querySelector('.admin-system-log__row') as HTMLElement).click();
    fixture.detectChanges();
    const req: TestRequest = http.expectOne('/api/v1/admin/system-log/runs/run-1');
    req.flush([
      entry({ id: 1, runId: 'run-1', details: [{ nodeId: 'n1', type: 'a', label: 'Start', outcome: 'deferred', error: null }] }),
      entry({ id: 2, runId: 'run-1', details: [{ nodeId: 'n3', type: 'b', label: 'Fortsetzung', outcome: 'port:0', error: null }] })
    ]);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Start');
    expect(text).toContain('Fortsetzung');
  });

  it('load more appends next page', () => {
    http.expectOne(r => r.url === '/api/v1/admin/system-log/jobs').flush([]);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/flows').flush([]);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/entries')
      .flush({ content: [entry({ id: 1, title: 'Erster' })], totalElements: 2, page: 0, size: 1 });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-test="load-more"]') as HTMLButtonElement).click();
    const req = http.expectOne(r => r.url === '/api/v1/admin/system-log/entries');
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ content: [entry({ id: 2, title: 'Zweiter' })], totalElements: 2, page: 1, size: 1 });
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.admin-system-log__row');
    expect(rows.length).toBe(2);
    expect(fixture.nativeElement.querySelector('[data-test="load-more"]')).toBeNull();
  });

  it('error on entries keeps job board', () => {
    http.expectOne(r => r.url === '/api/v1/admin/system-log/jobs').flush([failingJob]);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/flows').flush([]);
    http.expectOne(r => r.url === '/api/v1/admin/system-log/entries')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.admin-system-log__job--failing')).toBeTruthy();
  });

  const firstOfFive = () => ({ content: [entry({ id: 1 })], totalElements: 5, page: 0, size: 1 });

  it('filter change resets to page 0', () => {
    flush([]);
    (fixture.nativeElement.querySelector('input[data-test="problems-only"]') as HTMLInputElement).click();
    const req = entriesRequests();
    expect(req[0].request.params.get('page')).toBe('0');
    http.match(r => r.url.endsWith('/flows')).forEach(r => r.flush([]));
    req[0].flush(page([]));
  });

  it('discards stale load-more response after a filter change', () => {
    http.expectOne(r => r.url.endsWith('/jobs')).flush([]);
    http.expectOne(r => r.url.endsWith('/flows')).flush([]);
    http.expectOne(r => r.url.endsWith('/entries')).flush(firstOfFive());
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-test="load-more"]') as HTMLButtonElement).click();
    const stale = http.expectOne(r => r.url.endsWith('/entries'));
    (fixture.nativeElement.querySelector('input[data-test="problems-only"]') as HTMLInputElement).click();
    http.expectOne(r => r.url.endsWith('/flows')).flush([]);
    const fresh = http.expectOne(r => r.url.endsWith('/entries'));
    fresh.flush({ content: [entry({ id: 10, title: 'Neu' })], totalElements: 1, page: 0, size: 1 });
    stale.flush({ content: [entry({ id: 99, title: 'Alt' })], totalElements: 5, page: 1, size: 1 });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Neu');
    expect(text).not.toContain('Alt');
  });

  it('uses the same frozen from on page 0 and page 1', () => {
    http.expectOne(r => r.url.endsWith('/jobs')).flush([]);
    http.expectOne(r => r.url.endsWith('/flows')).flush([]);
    const first = http.expectOne(r => r.url.endsWith('/entries'));
    const from0 = first.request.params.get('from');
    first.flush(firstOfFive());
    fixture.detectChanges();
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(Date.now() + 3_600_000));
    (fixture.nativeElement.querySelector('[data-test="load-more"]') as HTMLButtonElement).click();
    const second = http.expectOne(r => r.url.endsWith('/entries'));
    jasmine.clock().uninstall();
    expect(second.request.params.get('page')).toBe('1');
    expect(second.request.params.get('from')).toBe(from0);
    second.flush({ content: [], totalElements: 1, page: 1, size: 1 });
  });

  it('keeps applied search text for load more until search is submitted', () => {
    http.expectOne(r => r.url.endsWith('/jobs')).flush([]);
    http.expectOne(r => r.url.endsWith('/flows')).flush([]);
    http.expectOne(r => r.url.endsWith('/entries')).flush(firstOfFive());
    fixture.detectChanges();
    fixture.componentInstance.searchText = 'getippt';
    (fixture.nativeElement.querySelector('[data-test="load-more"]') as HTMLButtonElement).click();
    const req = http.expectOne(r => r.url.endsWith('/entries'));
    expect(req.request.params.has('q')).toBeFalse();
    req.flush({ content: [], totalElements: 1, page: 1, size: 1 });
  });

  it('renders LOG stacktrace in pre', () => {
    flush([entry({ kind: 'LOG', status: 'WARN', details: 'java.lang.Boom\n\tat X.y(X.java:1)' })]);
    (fixture.nativeElement.querySelector('.admin-system-log__row') as HTMLElement).click();
    fixture.detectChanges();
    const pre = fixture.nativeElement.querySelector('pre') as HTMLElement;
    expect(pre.textContent).toContain('java.lang.Boom');
  });

  it('keeps at least one kind checked', () => {
    flush([]);
    fixture.componentInstance.selectedKinds = new Set<'LOG'>(['LOG']);
    fixture.componentInstance.toggleKind('LOG');
    expect(fixture.componentInstance.selectedKinds.size).toBe(1);
  });
});
