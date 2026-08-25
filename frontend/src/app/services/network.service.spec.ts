import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NetworkService } from './network.service';
import {
  NetworkDeviceAdminResponse,
  NetworkDeviceRequest,
  NetworkHistoryResponse,
  NetworkStatusResponse
} from '../models/network.model';

describe('NetworkService', () => {
  let service: NetworkService;
  let httpMock: HttpTestingController;

  const status: NetworkStatusResponse = {
    online: true,
    latencyMs: 12,
    gatewayReachable: true,
    lastCheckedAt: '2026-08-25T10:00:00',
    lastSpeedtest: {
      testedAt: '2026-08-25T09:00:00',
      downloadMbps: 100,
      uploadMbps: 20,
      success: true,
      errorMessage: null
    },
    devices: [{ id: 1, name: 'Router', host: '192.168.1.1', reachable: true, lastSeenAt: '2026-08-25T10:00:00' }]
  };

  const history: NetworkHistoryResponse = {
    latency: [{ time: '2026-08-25T10:00:00', value: 12 }],
    speedtests: [{ time: '2026-08-25T09:00:00', downloadMbps: 100, uploadMbps: 20 }]
  };

  const device: NetworkDeviceAdminResponse = {
    id: 1,
    name: 'Router',
    host: '192.168.1.1',
    tcpPort: null,
    sortOrder: 0,
    active: true
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(NetworkService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt den aktuellen Status', () => {
    let result: NetworkStatusResponse | undefined;
    service.getStatus().subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/status');
    expect(req.request.method).toBe('GET');
    req.flush(status);

    expect(result).toEqual(status);
  });

  it('laedt die Historie fuer einen Zeitraum', () => {
    let result: NetworkHistoryResponse | undefined;
    service.getHistory('WEEK').subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/history?range=WEEK');
    expect(req.request.method).toBe('GET');
    req.flush(history);

    expect(result).toEqual(history);
  });

  it('startet einen Speedtest', () => {
    let result: unknown;
    service.runSpeedtest().subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/speedtest');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(status.lastSpeedtest);

    expect(result).toEqual(status.lastSpeedtest);
  });

  it('laedt die pflegbaren Geraete', () => {
    let result: NetworkDeviceAdminResponse[] | undefined;
    service.getDevices().subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/devices');
    expect(req.request.method).toBe('GET');
    req.flush([device]);

    expect(result).toEqual([device]);
  });

  it('legt ein Geraet an', () => {
    const request: NetworkDeviceRequest = { name: 'Router', host: '192.168.1.1' };
    let result: NetworkDeviceAdminResponse | undefined;
    service.createDevice(request).subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/devices');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(device);

    expect(result).toEqual(device);
  });

  it('aktualisiert ein Geraet', () => {
    const request: NetworkDeviceRequest = { name: 'Router2', host: '192.168.1.2' };
    let result: NetworkDeviceAdminResponse | undefined;
    service.updateDevice(1, request).subscribe((res) => (result = res));

    const req = httpMock.expectOne('/api/v1/network/devices/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(request);
    req.flush(device);

    expect(result).toEqual(device);
  });

  it('loescht ein Geraet', () => {
    let completed = false;
    service.deleteDevice(1).subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne('/api/v1/network/devices/1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBeTrue();
  });
});
