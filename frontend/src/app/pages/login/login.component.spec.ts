import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('holt beim Oeffnen das XSRF-Cookie ueber einen GET', () => {
    // Die Login-Route laeuft ohne Guard — ohne diesen GET gibt es vor dem
    // Login-POST kein XSRF-Cookie und Spring antwortet mit 403
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/v1/auth/me');
    expect(req.request.method).toBe('GET');
    req.flush('nicht angemeldet', { status: 401, statusText: 'Unauthorized' });
  });
});
