import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, MarkAreaComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { ThermostatTileComponent } from '../../components/thermostat-tile/thermostat-tile.component';
import { ThermostatService } from '../../services/thermostat.service';
import { Thermostat, ThermostatMode, ThermostatRange } from '../../models/thermostat.model';
import { formatTemp, gridColumns } from '../../shared/thermostat-view.util';
import { buildSparklineOptions } from './heating-chart.util';

echarts.use([LineChart, GridComponent, MarkAreaComponent, CanvasRenderer]);

interface RangeOption {
  value: ThermostatRange;
  label: string;
}

/** Feste Stufen der Sammelaktion "Alle auf ...". */
const BULK_LEVELS: readonly number[] = [17, 21];

/**
 * Heizungsansicht fuer das Wandtablet: je Raum eine Kachel mit Ring-Zifferblatt,
 * Bedienung und Verlauf. Die Sammelaktion fragt vor dem Schreiben nach - das ist
 * der einzige Schutz, KIOSK darf serverseitig schreiben.
 */
@Component({
  selector: 'app-tablet-heating',
  standalone: true,
  imports: [TabletShellComponent, ThermostatTileComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-heating.component.html',
  styleUrl: './tablet-heating.component.scss'
})
export class TabletHeatingComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 60_000;

  private readonly thermostatService = inject(ThermostatService);
  private refreshTimer: number | null = null;
  /** Laufende Verlaufsabrufe, damit ein Zeitraumwechsel sie ablaesen kann. */
  private pendingHistory = new Subscription();

  readonly ranges: RangeOption[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' }
  ];
  readonly bulkLevels = BULK_LEVELS;
  readonly formatTemp = formatTemp;

  activeRange: ThermostatRange = 'DAY';

  thermostats: Thermostat[] = [];
  loaded = false;
  loadError: string | null = null;

  /**
   * Chart-Optionen je Thermostat als Felder, die nur bei Datenaenderung neu
   * entstehen. Ein Methodenaufruf im Template lieferte bei jeder Change-
   * Detection ein neues Objekt, und ngx-echarts zeichnete jedes Mal komplett
   * neu (notMerge) - Flackern auf der Wandanzeige.
   */
  readonly sparklines = new Map<number, Record<string, unknown> | null>();
  readonly busyIds = new Set<number>();
  readonly tileErrors = new Map<number, string>();

  /** Gewaehlte Zieltemperatur der Sammelaktion; der Dialog haelt nur diese, nie die Zielliste. */
  bulkTemp: number | null = null;
  bulkBusy = false;
  bulkError: string | null = null;

  ngOnInit(): void {
    this.loadThermostats(false);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletHeatingComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
    this.pendingHistory.unsubscribe();
  }

  get columns(): number {
    return gridColumns(this.thermostats.length);
  }

  get onlineCount(): number {
    return this.thermostats.filter(thermostat => thermostat.online).length;
  }

  setRange(range: ThermostatRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.loadHistories(false);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.loadThermostats(true);
  }

  setTarget(thermostat: Thermostat, targetTemp: number): void {
    this.write(thermostat.id, this.thermostatService.setTarget(thermostat.id, targetTemp));
  }

  setMode(thermostat: Thermostat, mode: ThermostatMode): void {
    this.write(thermostat.id, this.thermostatService.setMode(thermostat.id, mode));
  }

  openBulk(temp: number): void {
    this.bulkError = null;
    this.bulkTemp = temp;
  }

  cancelBulk(): void {
    this.bulkTemp = null;
    this.bulkError = null;
  }

  /**
   * Loest die Ziele erst jetzt aus der AKTUELLEN Liste auf (Regel confirmToggle):
   * ein Refresh bei offenem Dialog darf kein inzwischen offline gegangenes
   * Thermostat mitschalten.
   */
  confirmBulk(): void {
    const temp = this.bulkTemp;
    if (temp === null) {
      return;
    }
    const ids = this.thermostats.filter(thermostat => thermostat.online).map(thermostat => thermostat.id);
    if (ids.length === 0) {
      this.cancelBulk();
      return;
    }
    this.bulkBusy = true;
    this.thermostatService.bulk(ids, temp).subscribe({
      next: updated => {
        this.bulkBusy = false;
        this.bulkTemp = null;
        this.mergeThermostats(updated);
      },
      error: (error: HttpErrorResponse) => {
        console.error('Sammelaktion fehlgeschlagen:', error);
        this.bulkBusy = false;
        this.bulkError = error.error?.message ?? 'Sammelaktion fehlgeschlagen.';
        this.reload();
      }
    });
  }

  private write(id: number, request: ReturnType<ThermostatService['setTarget']>): void {
    this.busyIds.add(id);
    this.tileErrors.delete(id);
    request.subscribe({
      next: updated => {
        this.busyIds.delete(id);
        this.mergeThermostats([updated]);
      },
      error: (error: HttpErrorResponse) => {
        console.error('Thermostat konnte nicht gesetzt werden:', error);
        this.busyIds.delete(id);
        this.tileErrors.set(id, error.error?.message ?? 'Befehl fehlgeschlagen.');
        // Zurueck auf den Serverwert: die Kachel verwirft ihren optimistischen
        // Stand, sobald ein frisches Objekt ankommt.
        this.reload();
      }
    });
  }

  private mergeThermostats(updated: Thermostat[]): void {
    const byId = new Map(updated.map(thermostat => [thermostat.id, thermostat]));
    this.thermostats = this.thermostats.map(thermostat => byId.get(thermostat.id) ?? thermostat);
  }

  private loadThermostats(silent: boolean): void {
    if (!silent) {
      this.loadError = null;
    }
    this.thermostatService.list().subscribe({
      next: list => {
        this.thermostats = list;
        this.loaded = true;
        this.loadError = null;
        this.loadHistories(true);
      },
      error: (error: HttpErrorResponse) => {
        console.error('Fehler beim Laden der Thermostate:', error);
        // Alte Werte sind auf einer Wandanzeige mehr wert als gar keine.
        if (!silent) {
          this.loadError = 'Thermostate konnten nicht geladen werden.';
        }
      }
    });
  }

  private loadHistories(silent: boolean): void {
    // Laufende Abrufe abbestellen, bevor die naechsten starten - sonst koennte
    // eine aeltere, langsamere Antwort die Diagramme zum falschen Zeitraum
    // ueberschreiben.
    this.pendingHistory.unsubscribe();
    this.pendingHistory = new Subscription();
    const range = this.activeRange;

    for (const thermostat of this.thermostats) {
      this.pendingHistory.add(
        this.thermostatService.history(thermostat.id, range).subscribe({
          next: history => {
            this.sparklines.set(thermostat.id, buildSparklineOptions(history.points));
          },
          error: (error: HttpErrorResponse) => {
            console.error('Verlauf konnte nicht geladen werden:', error);
            if (!silent) {
              this.sparklines.set(thermostat.id, null);
            }
          }
        })
      );
    }
  }
}
