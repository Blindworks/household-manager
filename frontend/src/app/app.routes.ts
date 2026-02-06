import { Routes } from '@angular/router';

/**
 * Application routes configuration.
 * Uses lazy loading for better performance.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent),
    title: 'Dashboard - Household Manager'
  },
  {
    path: 'marketing',
    loadComponent: () => import('./pages/marketing/marketing.component').then(m => m.MarketingComponent),
    title: 'Household Manager - Haushaltsverwaltung leicht gemacht'
  },
  {
    path: 'meter-readings',
    loadComponent: () => import('./pages/meter-readings/meter-readings.component').then(m => m.MeterReadingsComponent),
    title: 'Zählerstände - Household Manager'
  },
  {
    path: 'utility-prices',
    loadComponent: () => import('./pages/utility-prices/utility-prices.component').then(m => m.UtilityPricesComponent),
    title: 'Versorgerpreise - Household Manager'
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full'
  }
];
