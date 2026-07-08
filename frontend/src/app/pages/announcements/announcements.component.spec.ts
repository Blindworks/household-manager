import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AnnouncementsComponent } from './announcements.component';

describe('AnnouncementsComponent', () => {
  let fixture: ComponentFixture<AnnouncementsComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnnouncementsComponent, HttpClientTestingModule]
    }).compileComponents();
    fixture = TestBed.createComponent(AnnouncementsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('laedt beim Init den Auth-Status', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/v1/alexa/auth/status');
    req.flush({ loggedIn: false, reauthRequired: false });
    expect(fixture.componentInstance.authStatus()?.loggedIn).toBeFalse();
    httpMock.verify();
  });
});
