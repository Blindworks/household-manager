import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminAuditLogComponent } from './admin-audit-log.component';
import { AuditEntry, AuditPage } from '../../models/auth.model';

const BASE = '/api/v1/admin/audit-log';

function entry(id: number, extra: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id, timestamp: '2026-10-23T07:03:27', actorType: 'SYSTEM', actor: 'FLOW:13', action: 'switch.device',
    detail: '29 -> off', actorLabel: 'Flow „Nacht“', actionLabel: 'Gerät geschaltet', detailLabel: 'Kamera → aus',
    ...extra
  };
}

function page(content: AuditEntry[], totalElements: number, pageNo = 0): AuditPage {
  return { content, totalElements, page: pageNo, size: 50 };
}

describe('AdminAuditLogComponent', () => {
  let fixture: ComponentFixture<AdminAuditLogComponent>;
  let component: AdminAuditLogComponent;
  let http: HttpTestingController;

  const searchRequest = (): TestRequest =>
    http.expectOne(req => req.url === BASE && req.method === 'GET');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminAuditLogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminAuditLogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    http.expectOne(`${BASE}/actions`).flush([{ action: 'switch.device', label: 'Gerät geschaltet' }]);
  });

  afterEach(() => http.verify());

  it('renders readable labels and the total count', () => {
    searchRequest().flush(page([entry(1)], 1));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Kamera → aus');
    expect(text).toContain('1 Eintrag');
  });

  it('default request has no filters and page 0', () => {
    const req = searchRequest();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.has('from')).toBeFalse();
    expect(req.request.params.has('actorKind')).toBeFalse();
    expect(req.request.params.has('q')).toBeFalse();
    req.flush(page([], 0));
  });

  it('unchecking a kind sends the remaining kinds', () => {
    searchRequest().flush(page([], 0));
    component.toggleKind('SYSTEM');

    const req = searchRequest();
    expect(req.request.params.getAll('actorKind')).toEqual(['PERSON', 'FLOW', 'AGENT', 'TELEGRAM', 'SERVICE']);
    req.flush(page([], 0));
  });

  it('the last kind cannot be unchecked', () => {
    searchRequest().flush(page([], 0));
    for (const kind of ['PERSON', 'FLOW', 'AGENT', 'TELEGRAM', 'SERVICE'] as const) {
      component.toggleKind(kind);
      searchRequest().flush(page([], 0));
    }
    component.toggleKind('SYSTEM');

    http.expectNone(req => req.url === BASE);
    expect(component.selectedKinds.has('SYSTEM')).toBeTrue();
  });

  it('search text and action are sent only after applying', () => {
    searchRequest().flush(page([], 0));
    component.searchText = '  Kamera ';
    component.actionFilter = 'switch.device';
    component.applyFilters();

    const req = searchRequest();
    expect(req.request.params.get('q')).toBe('Kamera');
    expect(req.request.params.get('action')).toBe('switch.device');
    req.flush(page([], 0));
  });

  it('load more reuses the applied filter and frozen from, and appends without duplicates', fakeAsync(() => {
    searchRequest().flush(page([], 0));
    component.rangeKey = 'D7';
    component.searchText = 'Kamera';
    component.applyFilters();
    const first = searchRequest();
    const from = first.request.params.get('from');
    first.flush(page([entry(1), entry(2)], 3));

    component.searchText = 'anders';
    tick(2000);
    component.loadMore();
    const second = searchRequest();
    expect(second.request.params.get('page')).toBe('1');
    expect(second.request.params.get('from')).toBe(from);
    expect(second.request.params.get('q')).toBe('Kamera');
    second.flush(page([entry(2), entry(3)], 3, 1));

    expect(component.entries.map(e => e.id)).toEqual([1, 2, 3]);
    expect(component.hasMore).toBeFalse();
  }));

  it('discards a stale load-more response after a filter change', () => {
    searchRequest().flush(page([entry(1)], 2));
    component.loadMore();
    const stale = searchRequest();
    component.searchText = 'neu';
    component.applyFilters();
    const fresh = searchRequest();

    stale.flush(page([entry(2)], 2, 1));
    fresh.flush(page([entry(9)], 1));

    expect(component.entries.map(e => e.id)).toEqual([9]);
  });

  it('shows the error and keeps working on failure', () => {
    searchRequest().flush('x', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component.errorMessage).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });
});
