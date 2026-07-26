import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let httpClient: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;
  let authService: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });
    httpClient = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    authService = TestBed.inject(AuthService);
  });

  afterEach(() => httpMock.verify());

  it('navigiert bei 401 zur Login-Seite mit returnUrl', () => {
    spyOnProperty(router, 'url', 'get').and.returnValue('/devices');
    spyOn(router, 'navigate');
    spyOn(authService, 'clearUser');

    httpClient.get('/api/v1/switches').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/v1/switches').flush('nein', { status: 401, statusText: 'Unauthorized' });

    expect(authService.clearUser).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/devices' } });
  });

  it('navigiert nicht erneut, wenn bereits auf der Login-Seite (Race bei parallelen 401)', () => {
    spyOnProperty(router, 'url', 'get').and.returnValue('/login?returnUrl=%2Fdevices');
    spyOn(router, 'navigate');
    spyOn(authService, 'clearUser');

    httpClient.get('/api/v1/switches').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/v1/switches').flush('nein', { status: 401, statusText: 'Unauthorized' });

    expect(authService.clearUser).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
