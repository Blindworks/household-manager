import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * Dashboard component - main application dashboard.
 * Displays overview of household utilities and meter readings.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  /**
   * Meter reading data for display.
   * TODO: Replace with actual API calls in future implementation.
   */
  readonly meterData: ReadonlyArray<MeterCardData> = [
    {
      type: 'Strom',
      icon: '⚡',
      color: '#f59e0b',
      lastReading: 15234.5,
      unit: 'kWh',
      consumption: 287.3,
      consumptionPeriod: 'letzten 30 Tage',
      trend: 'up'
    },
    {
      type: 'Gas',
      icon: '🔥',
      color: '#3b82f6',
      lastReading: 8456.2,
      unit: 'm³',
      consumption: 156.8,
      consumptionPeriod: 'letzten 30 Tage',
      trend: 'down'
    },
    {
      type: 'Wasser',
      icon: '💧',
      color: '#10b981',
      lastReading: 3287.1,
      unit: 'm³',
      consumption: 12.4,
      consumptionPeriod: 'letzten 30 Tage',
      trend: 'stable'
    }
  ];

  ngOnInit(): void {
    // TODO: Load actual meter data from backend API
  }

  /**
   * Gets the trend icon based on consumption trend.
   */
  getTrendIcon(trend: string): string {
    switch (trend) {
      case 'up':
        return '↗️';
      case 'down':
        return '↘️';
      case 'stable':
        return '→';
      default:
        return '→';
    }
  }

  /**
   * Gets the trend CSS class based on consumption trend.
   */
  getTrendClass(trend: string): string {
    return `trend--${trend}`;
  }
}

/**
 * Interface for meter card data display.
 */
interface MeterCardData {
  readonly type: string;
  readonly icon: string;
  readonly color: string;
  readonly lastReading: number;
  readonly unit: string;
  readonly consumption: number;
  readonly consumptionPeriod: string;
  readonly trend: 'up' | 'down' | 'stable';
}
