import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TractiveAccountComponent } from './tractive-account.component';

describe('TractiveAccountComponent', () => {
  let fixture: ComponentFixture<TractiveAccountComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TractiveAccountComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(TractiveAccountComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('meldet sich an und vergisst das Passwort danach', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/tractive/status').flush({ authenticated: false });
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.email = 'toni@example.com';
    component.password = 'geheim';

    component.login();

    const req = httpMock.expectOne('/api/v1/tractive/login');
    expect(req.request.body).toEqual({ email: 'toni@example.com', password: 'geheim' });
    req.flush({ authenticated: true, email: 'toni@example.com' });
    fixture.detectChanges();

    expect(component.password).toBe('');
    expect(component.connected()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Verbunden als toni@example.com');
  });

  it('unterscheidet falsche Zugangsdaten von einer nicht erreichbaren Cloud', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/tractive/status').flush({ authenticated: false });
    const component = fixture.componentInstance;

    component.login();
    httpMock.expectOne('/api/v1/tractive/login').flush(null, { status: 502, statusText: 'Bad Gateway' });

    expect(component.authError()).toContain('nicht erreichbar');
  });
});
