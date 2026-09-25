import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
  BehaviorSubject,
  EMPTY,
  Observable,
  catchError,
  distinctUntilChanged,
  ignoreElements,
  map,
  merge,
  share,
  switchMap,
  tap,
  timer
} from 'rxjs';
import { AppearanceSettings, DashboardTheme } from '../models/appearance.model';

/** Das Wandtablet hat das Dashboard dauerhaft offen und muss eine Umstellung selbst bemerken. */
const REFRESH_INTERVAL_MS = 60_000;
const CACHE_KEY = 'lumina.dashboardTheme';

/**
 * Hell/Dunkel des Dashboards und der Tablet-Ansichten. Die Einstellung gilt global
 * (`GET/PUT /v1/appearance`, gepflegt unter Admin -> Darstellung).
 *
 * `dashboardTheme$` gleicht sich minuetlich mit dem Server ab, solange jemand es
 * abonniert hat. Der zuletzt bekannte Wert liegt zusaetzlich im localStorage,
 * damit ein Neuladen nicht erst dunkel aufblitzt, bevor die Antwort da ist. Ein
 * fehlgeschlagener Abruf behaelt den letzten Stand.
 */
@Injectable({ providedIn: 'root' })
export class AppearanceService {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/v1/appearance';
  private readonly theme = new BehaviorSubject<DashboardTheme>(readCachedTheme());

  private readonly sync$ = timer(0, REFRESH_INTERVAL_MS).pipe(
    switchMap(() => this.get().pipe(catchError(() => EMPTY))),
    tap(settings => this.apply(settings.dashboardTheme)),
    ignoreElements(),
    share()
  );

  readonly dashboardTheme$: Observable<DashboardTheme> = merge(this.theme, this.sync$).pipe(
    distinctUntilChanged()
  );

  /** Fuer `[class.lumina--light]` an den lumina-Wurzelelementen. */
  readonly isLight$: Observable<boolean> = this.dashboardTheme$.pipe(map(theme => theme === 'LIGHT'));

  get(): Observable<AppearanceSettings> {
    return this.http.get<AppearanceSettings>(this.url);
  }

  save(dashboardTheme: DashboardTheme): Observable<AppearanceSettings> {
    return this.http.put<AppearanceSettings>(this.url, { dashboardTheme }).pipe(
      tap(settings => this.apply(settings.dashboardTheme))
    );
  }

  private apply(value: DashboardTheme | null | undefined): void {
    const theme = normalizeTheme(value);
    writeCachedTheme(theme);
    this.theme.next(theme);
  }
}

/** Alles ausser LIGHT ist das bisherige dunkle Design - wie der Default im Backend. */
export function normalizeTheme(value: unknown): DashboardTheme {
  return value === 'LIGHT' ? 'LIGHT' : 'DARK';
}

function readCachedTheme(): DashboardTheme {
  try {
    return normalizeTheme(localStorage.getItem(CACHE_KEY));
  } catch {
    return 'DARK';
  }
}

function writeCachedTheme(theme: DashboardTheme): void {
  try {
    localStorage.setItem(CACHE_KEY, theme);
  } catch {
    // Privater Modus o. ae.: dann blitzt beim Neuladen kurz das dunkle Design auf.
  }
}
