import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AuthService } from '../../services/auth.service';
import { provideRouter } from '@angular/router';

import { HeaderComponent } from './header.component';

describe('HeaderComponent', () => {
  let component: HeaderComponent;
  let fixture: ComponentFixture<HeaderComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(HeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  function loginAs(role: 'ADMIN' | 'MEMBER' | 'KIOSK'): HTMLElement {
    TestBed.inject(AuthService).ensureLoaded().subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/me')
      .flush({ id: 1, username: 'bene', displayName: 'Bene', role, mustChangePassword: false });
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function topLabels(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('.header__nav--desktop > .header__nav-list > .header__nav-item > .header__nav-link'))
      .map(node => node.textContent!.replace('▾', '').trim());
  }

  it('zeigt dem Admin die sieben Hauptbereiche', () => {
    const el = loginAs('ADMIN');
    expect(topLabels(el)).toEqual(['Home', 'Haushalt', 'Energie', 'Umwelt', 'Smart Home', 'Ausgaben', 'Admin']);
  });

  it('gliedert das Admin-Menü in Abschnitte nebeneinander', () => {
    const el = loginAs('ADMIN');
    component.toggleSubmenu('admin');
    fixture.detectChanges();
    const dropdown = el.querySelector('.header__nav--desktop .header__nav-submenu--columns')!;
    expect(Array.from(dropdown.querySelectorAll('.header__nav-section-title')).map(t => t.textContent!.trim()))
      .toEqual(['System', 'Integrationen', 'Haus & Dashboard']);
  });

  it('zeigt oben rechts Avatar und Namen, das Menü erst nach dem Antippen', () => {
    const el = loginAs('ADMIN');
    const desktop = el.querySelector('.header__nav--desktop')!;
    expect(desktop.querySelector('.header__avatar')!.textContent!.trim()).toBe('B');
    expect(desktop.querySelector('.header__user-menu')).toBeNull();

    (desktop.querySelector('.header__user-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();

    const menu = desktop.querySelector('.header__user-menu')!;
    expect(menu.textContent).toContain('Administrator · bene');
    expect(Array.from(menu.querySelectorAll('.header__user-item')).map(i => i.textContent!.replace(/key|tablet|logout/, '').trim()))
      .toEqual(['Passwort ändern', 'Tablet-Ansicht', 'Abmelden']);
  });

  it('schließt offene Menüs bei einem Klick außerhalb des Headers', () => {
    loginAs('ADMIN');
    component.toggleSubmenu(component.USER_MENU);
    document.body.click();
    expect(component.expandedMenu()).toBeNull();
  });

  it('öffnet nie Nutzer- und Bereichsmenü gleichzeitig', () => {
    loginAs('ADMIN');
    component.toggleSubmenu(component.USER_MENU);
    component.toggleSubmenu('admin');
    expect(component.isSubmenuExpanded(component.USER_MENU)).toBeFalse();
  });

  it('blendet Admin und Ausgaben für das Wandtablet aus', () => {
    const el = loginAs('KIOSK');
    expect(topLabels(el)).toEqual(['Home', 'Haushalt', 'Energie', 'Umwelt', 'Smart Home']);
  });
});
