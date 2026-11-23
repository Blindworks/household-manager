import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
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

  function text(fixture: { nativeElement: HTMLElement }, selector: string): string {
    return fixture.nativeElement.querySelector(selector)?.textContent?.trim() ?? '';
  }

  [
    { form: { username: ' ', displayName: 'Neu', password: 'geheim123' }, hint: 'Benutzernamen' },
    { form: { username: 'neu', displayName: '', password: 'geheim123' }, hint: 'Anzeigenamen' },
    { form: { username: 'neu', displayName: 'Neu', password: 'kurz' }, hint: 'mindestens 8 Zeichen' }
  ].forEach(({ form, hint }) => {
    it(`nennt beim Anlegen den Grund statt still nichts zu tun (${hint})`, () => {
      const fixture = TestBed.createComponent(AdminUsersComponent);
      fixture.detectChanges();
      fixture.componentInstance.form = { ...form, role: 'MEMBER', wallTablet: false };

      fixture.componentInstance.create();
      fixture.detectChanges();

      expect(service.createUser).not.toHaveBeenCalled();
      expect(text(fixture, '.admin-users__error')).toContain(hint);
    });
  });

  it('bestätigt das Speichern und räumt einen alten Fehler weg', () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();
    service.updateUser.and.returnValue(throwError(() => new Error('Kaputt')));
    fixture.componentInstance.save({ ...tablet });
    fixture.detectChanges();
    expect(text(fixture, '.admin-users__error')).toBe('Kaputt');

    service.updateUser.and.returnValue(of(tablet));
    fixture.componentInstance.save({ ...tablet });
    fixture.detectChanges();

    expect(text(fixture, '.admin-users__error')).toBe('');
    expect(text(fixture, '.admin-users__success')).toContain('gespeichert');
  });

  it('meldet ein zu kurzes neues Passwort', () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();
    spyOn(window, 'prompt').and.returnValue('kurz');

    fixture.componentInstance.changePassword(tablet);
    fixture.detectChanges();

    expect(service.setPassword).not.toHaveBeenCalled();
    expect(text(fixture, '.admin-users__error')).toContain('mindestens 8 Zeichen');
  });

  it('behält ungespeicherte Eingaben anderer Zeilen bei Speichern, Fehler und Anlegen', () => {
    const bene: AppUser = { ...tablet, id: 3, username: 'benedikt', role: 'MEMBER', wallTablet: false };
    service.getUsers.and.callFake(() => of([{ ...tablet }, { ...bene }]));
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.users[1].role = 'ADMIN';

    service.updateUser.and.returnValue(of({ ...tablet, displayName: 'Vom Server' }));
    component.save(component.users[0]);
    expect(component.users[0].displayName).toBe('Vom Server');
    expect(component.users[1].role).toBe('ADMIN');

    service.updateUser.and.returnValue(throwError(() => new Error('Kaputt')));
    component.save(component.users[0]);
    expect(component.users[1].role).toBe('ADMIN');

    service.createUser.and.returnValue(of({ ...tablet, id: 10, username: 'neu' }));
    component.form = { username: 'neu', displayName: 'Neu', password: 'geheim123', role: 'MEMBER', wallTablet: false };
    component.create();
    expect(component.users.map(u => u.id)).toEqual([9, 3, 10]);
    expect(component.users[1].role).toBe('ADMIN');
  });
});
