import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';
import { ViewModeService } from './services/view-mode.service';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it(`should have the 'household-manager' title`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.title).toEqual('household-manager');
  });

  it('should render title', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('Hello, household-manager');
  });

  // Eigenes Setup mit Router/HTTP, weil der Header (nur ausserhalb des Tablet-Modus) beides braucht.
  describe('Tablet-Modus', () => {
    it('kappt das Layout auf Bildschirmhoehe, damit Listen scrollen statt der ganzen Seite', () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [AppComponent],
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
      });
      const fixture = TestBed.createComponent(AppComponent);
      const viewMode = TestBed.inject(ViewModeService);
      viewMode.isTabletView.set(true);
      fixture.detectChanges();

      const layout = (fixture.nativeElement as HTMLElement).querySelector('.app-layout')!;
      expect(layout.classList).toContain('app-layout--tablet');

      viewMode.isTabletView.set(false);
      fixture.detectChanges();
      expect(layout.classList).not.toContain('app-layout--tablet');
    });

    it('laesst den Hauptbereich unter seinen Inhalt schrumpfen, damit nichts aus dem Schirm ragt', () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [AppComponent],
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
      });
      const fixture = TestBed.createComponent(AppComponent);
      TestBed.inject(ViewModeService).isTabletView.set(true);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      const layout = host.querySelector('.app-layout') as HTMLElement;
      const main = host.querySelector('.main-content') as HTMLElement;
      // Ein Kind, das ohne Kette die Hoehe des Schirms sprengen wuerde (wie eine lange
      // Stationsliste), dazu ein fester Rahmen statt 100vh, damit der Test nicht vom
      // Karma-Fenster abhaengt.
      layout.style.height = '400px';
      layout.style.minHeight = '0';
      const tall = document.createElement('div');
      tall.style.height = '1000px';
      main.appendChild(tall);

      expect(main.getBoundingClientRect().height).toBeLessThanOrEqual(400);
      tall.remove();
    });
  });
});
