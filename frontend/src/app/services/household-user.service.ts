import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Ein Haushaltsmitglied, wie es die Personenauswahl braucht. */
export interface HouseholdUser {
  id: number;
  displayName: string;
  enabled: boolean;
}

/** Schlanke Nutzerliste fuer die Personenauswahl (nicht die Admin-Nutzerverwaltung). */
@Injectable({ providedIn: 'root' })
export class HouseholdUserService {
  private readonly http = inject(HttpClient);

  list(): Observable<HouseholdUser[]> {
    return this.http.get<HouseholdUser[]>('/api/v1/users');
  }
}
