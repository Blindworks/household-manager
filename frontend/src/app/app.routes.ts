import { Routes } from '@angular/router';
import { unsavedChangesGuard } from './pages/flows/unsaved-changes.guard';
import { authGuard, adminGuard } from './guards/auth.guard';

/**
 * Application routes configuration.
 * Uses lazy loading for better performance.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent),
    canActivate: [authGuard],
    title: 'Dashboard - Household Manager'
  },
  {
    path: 'marketing',
    loadComponent: () => import('./pages/marketing/marketing.component').then(m => m.MarketingComponent),
    canActivate: [authGuard],
    title: 'Household Manager - Haushaltsverwaltung leicht gemacht'
  },
  {
    path: 'meter-readings',
    loadComponent: () => import('./pages/meter-readings/meter-readings.component').then(m => m.MeterReadingsComponent),
    canActivate: [authGuard],
    title: 'Zaehlerstaende - Household Manager'
  },
  {
    path: 'consumption',
    loadComponent: () => import('./pages/consumption-charts/consumption-charts.component').then(m => m.ConsumptionChartsComponent),
    canActivate: [authGuard],
    title: 'Verbrauch - Household Manager'
  },
  {
    path: 'energy',
    loadComponent: () => import('./pages/energy/energy.component').then(m => m.EnergyComponent),
    canActivate: [authGuard],
    title: 'Energie - Household Manager'
  },
  {
    path: 'energy/battery-control',
    loadComponent: () => import('./pages/battery-control/battery-control.component').then(m => m.BatteryControlComponent),
    canActivate: [authGuard],
    title: 'Akku Steuerung - Household Manager'
  },
  {
    path: 'energy/history',
    loadComponent: () => import('./pages/energy-history/energy-history.component').then(m => m.EnergyHistoryComponent),
    canActivate: [authGuard],
    title: 'Energieverlauf - Household Manager'
  },
  {
    path: 'air-quality',
    loadComponent: () => import('./pages/airrohr-charts/airrohr-charts.component').then(m => m.AirrohrChartsComponent),
    canActivate: [authGuard],
    title: 'Luftqualitaet - Household Manager'
  },
  {
    path: 'weather',
    loadComponent: () => import('./pages/weather/weather.component').then(m => m.WeatherComponent),
    canActivate: [authGuard],
    title: 'Wetter - Household Manager'
  },
  {
    path: 'temperatures',
    loadComponent: () => import('./pages/temperatures/temperatures.component').then(m => m.TemperaturesComponent),
    canActivate: [authGuard],
    title: 'Temperaturen - Household Manager'
  },
  {
    path: 'tablet/temperatures',
    loadComponent: () => import('./pages/tablet-temperatures/tablet-temperatures.component').then(m => m.TabletTemperaturesComponent),
    canActivate: [authGuard],
    title: 'Temperaturen - Household Manager'
  },
  {
    path: 'tablet/air-quality',
    loadComponent: () => import('./pages/tablet-air-quality/tablet-air-quality.component').then(m => m.TabletAirQualityComponent),
    canActivate: [authGuard],
    title: 'Luftqualitaet Tablet - Household Manager'
  },
  {
    path: 'tablet/consumption',
    loadComponent: () => import('./pages/tablet-consumption/tablet-consumption.component').then(m => m.TabletConsumptionComponent),
    canActivate: [authGuard],
    title: 'Verbrauch Tablet - Household Manager'
  },
  {
    path: 'tablet/toni',
    loadComponent: () => import('./pages/tablet-toni/tablet-toni.component').then(m => m.TabletToniComponent),
    canActivate: [authGuard],
    title: 'Toni Tablet - Household Manager'
  },
  {
    path: 'utility-prices',
    loadComponent: () => import('./pages/utility-prices/utility-prices.component').then(m => m.UtilityPricesComponent),
    canActivate: [adminGuard],
    title: 'Versorgerpreise - Household Manager'
  },
  {
    path: 'devices',
    loadComponent: () => import('./pages/devices/devices.component').then(m => m.DevicesComponent),
    canActivate: [authGuard],
    title: 'Geraete - Household Manager'
  },
  {
    path: 'entities',
    loadComponent: () => import('./pages/entities/entities.component').then(m => m.EntitiesComponent),
    canActivate: [authGuard],
    title: 'Entitaeten - Household Manager'
  },
  {
    path: 'flows',
    loadComponent: () => import('./pages/flows/flow-list.component').then(m => m.FlowListComponent),
    canActivate: [adminGuard],
    title: 'Automatisierungen - Household Manager'
  },
  {
    path: 'flows/:id',
    loadComponent: () => import('./pages/flows/flow-editor.component').then(m => m.FlowEditorComponent),
    canActivate: [adminGuard],
    canDeactivate: [unsavedChangesGuard],
    title: 'Flow-Editor - Household Manager'
  },
  {
    path: 'custom-entities',
    loadComponent: () => import('./pages/custom-entities/custom-entities.component').then(m => m.CustomEntitiesComponent),
    canActivate: [authGuard],
    title: 'Eigene Helfer - Household Manager'
  },
  {
    path: 'announcements',
    loadComponent: () => import('./pages/announcements/announcements.component').then(m => m.AnnouncementsComponent),
    canActivate: [authGuard],
    title: 'Ansagen - Household Manager'
  },
  {
    path: 'admin/users',
    loadComponent: () => import('./pages/admin-users/admin-users.component').then(m => m.AdminUsersComponent),
    canActivate: [adminGuard],
    title: 'Nutzer - Household Manager'
  },
  {
    path: 'admin/service-tokens',
    loadComponent: () => import('./pages/admin-service-tokens/admin-service-tokens.component').then(m => m.AdminServiceTokensComponent),
    canActivate: [adminGuard],
    title: 'API-Tokens - Household Manager'
  },
  {
    path: 'admin/calendar-categories',
    loadComponent: () => import('./pages/admin-calendar-categories/admin-calendar-categories.component')
      .then(m => m.AdminCalendarCategoriesComponent),
    canActivate: [adminGuard],
    title: 'Kalender-Kategorien - Household Manager'
  },
  {
    path: 'admin/tractive',
    loadComponent: () => import('./pages/admin-tractive/admin-tractive.component').then(m => m.AdminTractiveComponent),
    canActivate: [adminGuard],
    title: 'Hundetracker-Einstellungen - Household Manager'
  },
  {
    path: 'admin/audit-log',
    loadComponent: () => import('./pages/admin-audit-log/admin-audit-log.component').then(m => m.AdminAuditLogComponent),
    canActivate: [adminGuard],
    title: 'Audit-Log - Household Manager'
  },
  {
    path: 'admin',
    loadComponent: () => import('./pages/admin/admin.component').then(m => m.AdminComponent),
    canActivate: [adminGuard],
    title: 'Admin - Household Manager'
  },
  {
    path: 'zigbee',
    loadComponent: () => import('./pages/zigbee/zigbee.component').then(m => m.ZigbeeComponent),
    canActivate: [authGuard],
    title: 'Zigbee-Sensoren - Household Manager'
  },
  {
    path: 'finance',
    loadComponent: () => import('./pages/finance-overview/finance-overview.component').then(m => m.FinanceOverviewComponent),
    canActivate: [authGuard],
    title: 'Ausgaben - Household Manager'
  },
  {
    path: 'finance/transactions',
    loadComponent: () => import('./pages/finance-transactions/finance-transactions.component').then(m => m.FinanceTransactionsComponent),
    canActivate: [authGuard],
    title: 'Transaktionen - Household Manager'
  },
  {
    path: 'finance/accounts',
    loadComponent: () => import('./pages/finance-accounts/finance-accounts.component').then(m => m.FinanceAccountsComponent),
    canActivate: [authGuard],
    title: 'Konten - Household Manager'
  },
  {
    path: 'finance/categories',
    loadComponent: () => import('./pages/finance-categories/finance-categories.component').then(m => m.FinanceCategoriesComponent),
    canActivate: [authGuard],
    title: 'Kategorien - Household Manager'
  },
  {
    path: 'finance/rules',
    loadComponent: () => import('./pages/finance-rules/finance-rules.component').then(m => m.FinanceRulesComponent),
    canActivate: [authGuard],
    title: 'Regeln - Household Manager'
  },
  {
    path: 'finance/budgets',
    loadComponent: () => import('./pages/finance-budgets/finance-budgets.component').then(m => m.FinanceBudgetsComponent),
    canActivate: [authGuard],
    title: 'Budgets - Household Manager'
  },
  {
    path: 'finance/recurring',
    loadComponent: () => import('./pages/finance-recurring/finance-recurring.component').then(m => m.FinanceRecurringComponent),
    canActivate: [authGuard],
    title: 'Wiederkehrende - Household Manager'
  },
  {
    path: 'waste-collection',
    loadComponent: () => import('./pages/waste-collection/waste-collection.component').then(m => m.WasteCollectionComponent),
    canActivate: [authGuard],
    title: 'Muellabfuhr - Household Manager'
  },
  {
    path: 'vision',
    loadComponent: () => import('./pages/vision/vision.component').then(m => m.VisionComponent),
    canActivate: [adminGuard],
    title: 'Gesichtserkennung - Household Manager'
  },
  {
    path: 'calendar',
    loadComponent: () => import('./pages/calendar/calendar.component').then(m => m.CalendarComponent),
    canActivate: [authGuard],
    title: 'Kalender - Household Manager'
  },
  {
    path: 'pets',
    loadComponent: () => import('./pages/pets/pets.component').then(m => m.PetsComponent),
    canActivate: [authGuard],
    title: 'Hundetracker - Household Manager'
  },
  {
    path: 'pet-food',
    loadComponent: () => import('./pages/pet-food/pet-food.component').then(m => m.PetFoodComponent),
    canActivate: [authGuard],
    title: 'Futtervorrat - Household Manager'
  },
  {
    path: 'notifications',
    loadComponent: () => import('./pages/notifications/notifications.component').then(m => m.NotificationsComponent),
    canActivate: [authGuard],
    title: 'Benachrichtigungen - Household Manager'
  },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent),
    title: 'Anmelden - Household Manager'
  },
  {
    path: 'change-password',
    loadComponent: () => import('./pages/change-password/change-password.component').then(m => m.ChangePasswordComponent),
    canActivate: [authGuard],
    title: 'Passwort ändern - Household Manager'
  },
  {
    path: '**',
    redirectTo: '',
    pathMatch: 'full'
  }
];
