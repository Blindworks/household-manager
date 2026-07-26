import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';
import { CurrentUser } from '../models/auth.model';

/** Erzwungener Passwortwechsel (Bootstrap-Admin "changeit") geht jeder Navigation vor. */
function forcedPasswordChange(user: CurrentUser, targetUrl: string, router: Router) {
  return user.mustChangePassword && !targetUrl.startsWith('/change-password')
    ? router.createUrlTree(['/change-password'])
    : null;
}

/** Nur mit Anmeldung; sonst Login mit Ruecksprung-URL. */
export const authGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map(user => {
    if (!user) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    }
    return forcedPasswordChange(user, state.url, router) ?? true;
  }));
};

/** Nur fuer ADMIN; angemeldete Nicht-Admins landen auf dem Dashboard. */
export const adminGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map(user => {
    if (!user) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    }
    const forced = forcedPasswordChange(user, state.url, router);
    if (forced) {
      return forced;
    }
    return user.role === 'ADMIN' ? true : router.createUrlTree(['/']);
  }));
};
