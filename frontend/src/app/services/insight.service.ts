import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VentilationAssessment } from '../models/ventilation.model';

/** Service fuer serverseitig berechnete Hub-Hinweise. */
@Injectable({ providedIn: 'root' })
export class InsightService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/insights';

  getVentilation(): Observable<VentilationAssessment> {
    return this.http.get<VentilationAssessment>(`${this.baseUrl}/ventilation`);
  }
}
