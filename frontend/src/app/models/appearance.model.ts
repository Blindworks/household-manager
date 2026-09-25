/** Farbwelt des Dashboards und der Tablet-Ansichten (global, in der Datenbank). */
export type DashboardTheme = 'DARK' | 'LIGHT';

export interface AppearanceSettings {
  dashboardTheme: DashboardTheme;
}
