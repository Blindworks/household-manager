import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AdminUsersComponent } from './admin-users.component';
import { UserAdminService } from '../../services/user-admin.service';
import { AppUser } from '../../models/auth.model';

describe('AdminUsersComponent', () => {
  let service: jasmine.SpyObj<UserAdminService>;

  const tablet: AppUser = {
    id: 9, username: 'tablet', displayName: 'Wandtablet', role: 'MEMBER',
    enabled: true, wallTablet: true, createdAt: '2026-11-23T10:00:00'
  };

  beforeEach(async () => {
    service = jasmine.createSpyObj('UserAdminService', ['getUsers', 'createUser', 'updateUser', 'setPassword']);
    service.getUsers.and.returnValue(of([tablet]));
    service.createUser.and.returnValue(of(tablet));
    service.updateUser.and.returnValue(of(tablet));
    await TestBed.configureTestingModule({
      imports: [AdminUsersComponent],
      providers: [{ provide: UserAdminService, useValue: service }]
    }).compileComponents();
  });

  it('sendet wallTablet beim Speichern einer Zeile immer mit', () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();

    fixture.componentInstance.save({ ...tablet });

    expect(service.updateUser).toHaveBeenCalledWith(9, jasmine.objectContaining({ wallTablet: true }));
  });

  it('legt einen Benutzer mit Wandtablet-Merkmal an und setzt das Formular danach zurück', () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.form = { username: 'tablet', displayName: 'Wandtablet', password: 'geheim123', role: 'MEMBER', wallTablet: true };

    component.create();

    expect(service.createUser).toHaveBeenCalledWith(jasmine.objectContaining({ wallTablet: true }));
    expect(component.form.wallTablet).toBeFalse();
  });

  it('zeigt das Wandtablet-Häkchen je Zeile', async () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const box = (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLInputElement>('.admin-users__table tbody input[type=checkbox]');
    expect(box.length).toBe(2);
    expect(box[1].checked).toBeTrue();
  });
});
