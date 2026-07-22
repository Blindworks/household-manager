import { Routes } from '@angular/router';
import { unsavedChangesGuard } from './pages/flows/unsaved-changes.guard';

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
    path: 'temperatures',
    loadComponent: () => import('./pages/temperatures/temperatures.component').then(m => m.TemperaturesComponent),
    title: 'Temperaturen - Household Manager'
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
    path: 'entities',
    loadComponent: () => import('./pages/entities/entities.component').then(m => m.EntitiesComponent),
    title: 'Entitaeten - Household Manager'
  },
  {
    path: 'flows',
    loadComponent: () => import('./pages/flows/flow-list.component').then(m => m.FlowListComponent),
    title: 'Automatisierungen - Household Manager'
  },
  {
    path: 'flows/:id',
    loadComponent: () => import('./pages/flows/flow-editor.component').then(m => m.FlowEditorComponent),
    canDeactivate: [unsavedChangesGuard],
    title: 'Flow-Editor - Household Manager'
  },
  {
    path: 'custom-entities',
    loadComponent: () => import('./pages/custom-entities/custom-entities.component').then(m => m.CustomEntitiesComponent),
    title: 'Eigene Helfer - Household Manager'
  },
  {
    path: 'announcements',
    loadComponent: () => import('./pages/announcements/announcements.component').then(m => m.AnnouncementsComponent),
    title: 'Ansagen - Household Manager'
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
    path: 'finance',
    loadComponent: () => import('./pages/finance-overview/finance-overview.component').then(m => m.FinanceOverviewComponent),
    title: 'Ausgaben - Household Manager'
  },
  {
    path: 'finance/transactions',
    loadComponent: () => import('./pages/finance-transactions/finance-transactions.component').then(m => m.FinanceTransactionsComponent),
    title: 'Transaktionen - Household Manager'
  },
  {
    path: 'finance/accounts',
    loadComponent: () => import('./pages/finance-accounts/finance-accounts.component').then(m => m.FinanceAccountsComponent),
    title: 'Konten - Household Manager'
  },
  {
    path: 'finance/categories',
    loadComponent: () => import('./pages/finance-categories/finance-categories.component').then(m => m.FinanceCategoriesComponent),
    title: 'Kategorien - Household Manager'
  },
  {
    path: 'finance/rules',
    loadComponent: () => import('./pages/finance-rules/finance-rules.component').then(m => m.FinanceRulesComponent),
    title: 'Regeln - Household Manager'
  },
  {
    path: 'finance/budgets',
    loadComponent: () => import('./pages/finance-budgets/finance-budgets.component').then(m => m.FinanceBudgetsComponent),
    title: 'Budgets - Household Manager'
  },
  {
    path: 'finance/recurring',
    loadComponent: () => import('./pages/finance-recurring/finance-recurring.component').then(m => m.FinanceRecurringComponent),
    title: 'Wiederkehrende - Household Manager'
  },
  {
    path: 'waste-collection',
    loadComponent: () => import('./pages/waste-collection/waste-collection.component').then(m => m.WasteCollectionComponent),
    title: 'Muellabfuhr - Household Manager'
  },
  {
    path: 'vision',
    loadComponent: () => import('./pages/vision/vision.component').then(m => m.VisionComponent),
    title: 'Gesichtserkennung - Household Manager'
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full'
  }
];
