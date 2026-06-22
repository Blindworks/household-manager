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
    title: 'Zaehlerstaende - Household Manager'
  },
  {
    path: 'consumption',
    loadComponent: () => import('./pages/consumption-charts/consumption-charts.component').then(m => m.ConsumptionChartsComponent),
    title: 'Verbrauch - Household Manager'
  },
  {
    path: 'energy',
    loadComponent: () => import('./pages/energy/energy.component').then(m => m.EnergyComponent),
    title: 'Energie - Household Manager'
  },
  {
    path: 'energy/battery-control',
    loadComponent: () => import('./pages/battery-control/battery-control.component').then(m => m.BatteryControlComponent),
    title: 'Akku Steuerung - Household Manager'
  },
  {
    path: 'energy/history',
    loadComponent: () => import('./pages/energy-history/energy-history.component').then(m => m.EnergyHistoryComponent),
    title: 'Energieverlauf - Household Manager'
  },
  {
    path: 'air-quality',
    loadComponent: () => import('./pages/airrohr-charts/airrohr-charts.component').then(m => m.AirrohrChartsComponent),
    title: 'Luftqualitaet - Household Manager'
  },
  {
    path: 'weather',
    loadComponent: () => import('./pages/weather/weather.component').then(m => m.WeatherComponent),
    title: 'Wetter - Household Manager'
  },
  {
    path: 'utility-prices',
    loadComponent: () => import('./pages/utility-prices/utility-prices.component').then(m => m.UtilityPricesComponent),
    title: 'Versorgerpreise - Household Manager'
  },
  {
    path: 'devices',
    loadComponent: () => import('./pages/devices/devices.component').then(m => m.DevicesComponent),
    title: 'Geraete - Household Manager'
  },
  {
    path: 'admin',
    loadComponent: () => import('./pages/admin/admin.component').then(m => m.AdminComponent),
    title: 'Admin - Household Manager'
  },
  {
    path: 'zigbee',
    loadComponent: () => import('./pages/zigbee/zigbee.component').then(m => m.ZigbeeComponent),
    title: 'Zigbee-Sensoren - Household Manager'
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full'
  }
];
