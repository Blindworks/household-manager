import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UserAdminService } from './user-admin.service';
import { AppUser } from '../models/auth.model';

describe('UserAdminService', () => {
  let service: UserAdminService;
  let httpMock: HttpTestingController;

  const user: AppUser = {
    id: 1, username: 'mia', displayName: 'Mia', role: 'MEMBER', enabled: true,
    createdAt: '2026-07-25T10:00:00'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(UserAdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Nutzerliste', () => {
    service.getUsers().subscribe(result => expect(result).toEqual([user]));
    const req = httpMock.expectOne('/api/v1/admin/users');
    expect(req.request.method).toBe('GET');
    req.flush([user]);
  });

  it('legt einen Nutzer an', () => {
    service.createUser({ username: 'mia', displayName: 'Mia', password: 'geheim123', role: 'MEMBER' })
      .subscribe(result => expect(result).toEqual(user));
    const req = httpMock.expectOne('/api/v1/admin/users');
    expect(req.request.method).toBe('POST');
    req.flush(user);
  });
});
