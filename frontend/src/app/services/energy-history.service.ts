import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EnergyHistoryPoint, EnergyMetric } from '../models/energy-history.model';

@Injectable({
  providedIn: 'root'
})
export class EnergyHistoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/energy/history';

  getHistory(metric: EnergyMetric, hours = 24): Observable<EnergyHistoryPoint[]> {
    const params = new HttpParams()
      .set('metric', metric)
      .set('hours', hours.toString());

    return this.http.get<EnergyHistoryPoint[]>(this.baseUrl, { params });
  }
}
