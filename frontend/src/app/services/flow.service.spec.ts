import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FlowService } from './flow.service';
import { ValidationResult } from '../models/flow.model';

describe('FlowService', () => {
  let service: FlowService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(FlowService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists flows', () => {
    service.getFlows().subscribe();
    const req = httpMock.expectOne('/api/v1/flows');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('loads node types', () => {
    service.getNodeTypes().subscribe();
    const req = httpMock.expectOne('/api/v1/flows/node-types');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('creates a flow', () => {
    service.createFlow('Neu', 'Desc').subscribe();
    const req = httpMock.expectOne('/api/v1/flows');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Neu', description: 'Desc' });
    req.flush({});
  });

  it('saves draft via PUT', () => {
    service.saveDraft(1, 'Name', 'Desc', '{"nodes":[],"wires":[]}').subscribe();
    const req = httpMock.expectOne('/api/v1/flows/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.draftDefinition).toBe('{"nodes":[],"wires":[]}');
    req.flush({});
  });

  it('deploys and returns validation result', () => {
    let result: ValidationResult | undefined;
    service.deploy(1).subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/flows/1/deploy').flush({ errors: [], warnings: ['w'] });
    expect(result?.warnings).toEqual(['w']);
  });

  it('maps deploy 400 body to validation result', () => {
    let result: ValidationResult | undefined;
    service.deploy(1).subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/flows/1/deploy')
      .flush({ errors: ['kaputt'], warnings: [] }, { status: 400, statusText: 'Bad Request' });
    expect(result?.errors).toEqual(['kaputt']);
  });

  it('deploy falls back to a usable result on network error', () => {
    let result: ValidationResult | undefined;
    service.deploy(1).subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/flows/1/deploy').error(new ProgressEvent('error'));
    expect(result?.errors.length).toBeGreaterThan(0);
  });

  it('enables, disables, deletes, injects, reads debug', () => {
    service.setEnabled(1, true).subscribe();
    httpMock.expectOne('/api/v1/flows/1/enable').flush({});
    service.setEnabled(1, false).subscribe();
    httpMock.expectOne('/api/v1/flows/1/disable').flush({});
    service.deleteFlow(1).subscribe();
    const del = httpMock.expectOne('/api/v1/flows/1');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    service.inject(1, 'n1', { newState: '5' }).subscribe();
    const inj = httpMock.expectOne('/api/v1/flows/1/nodes/n1/inject');
    expect(inj.request.body).toEqual({ payload: { newState: '5' } });
    inj.flush(null);
    service.getDebug(1, 'n1').subscribe();
    httpMock.expectOne('/api/v1/flows/1/nodes/n1/debug').flush([]);
  });

  it('imports a flow via POST /import', () => {
    const payload = { schemaVersion: 1, name: 'X', description: '', definition: { nodes: [], wires: [] } };
    service.importFlow(payload).subscribe();
    const req = httpMock.expectOne('/api/v1/flows/import');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush({ id: 9, name: 'X', enabled: false, deployed: false });
  });
});
