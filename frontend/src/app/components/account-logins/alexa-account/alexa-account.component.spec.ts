import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AlexaAccountComponent } from './alexa-account.component';

describe('AlexaAccountComponent', () => {
  let fixture: ComponentFixture<AlexaAccountComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AlexaAccountComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AlexaAccountComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('zeigt den Proxy-Link und beendet das Warten, sobald die Anmeldung steht', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/alexa/auth/status').flush({ loggedIn: false, reauthRequired: false });
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component.startLogin();
    const start = httpMock.expectOne(r => r.method === 'POST');
    start.flush({ proxyUrl: 'http://192.168.1.100:3456/' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('a')?.getAttribute('href'))
      .toBe('http://192.168.1.100:3456/');

    tick(3000);
    httpMock.expectOne('/api/v1/alexa/auth/status')
      .flush({ loggedIn: true, reauthRequired: false, accountName: 'Benedikt' });
    fixture.detectChanges();

    expect(component.loginWaiting()).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Angemeldet als Benedikt');
    discardPeriodicTasks();
    httpMock.verify();
  }));
});
