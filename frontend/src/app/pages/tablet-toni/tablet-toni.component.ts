import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import * as L from 'leaflet';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { PetSupplyService } from '../../services/pet-supply.service';
import { TractiveService } from '../../services/tractive.service';
import { PetSupply } from '../../models/pet-supply.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import {
  PetSupplyTone,
  petSupplyBarWidth,
  petSupplyTone,
  worstPetSupplyTone
} from '../../shared/pet-supply-level.util';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';
import {
  walkDayLabel,
  walkDayTotals,
  walkDistance,
  walkDuration,
  walkTimeRange
} from '../../shared/walk-format.util';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
useLocalLeafletIcons();

/**
 * Hundeuebersicht fuer das Wandtablet: Vorraete, Spaziergaenge,
 * Tracker-Status und Position in einem 2x2-Raster, alles gleichzeitig sichtbar
 * und ohne Scrollen.
 *
 * Rein anzeigend. Buchungen bleiben der Seite /pet-food und dem
 * Dashboard-Dialog vorbehalten - auf dem Tablet laeuft die KIOSK-Rolle, dort
 * wuerde jede Buchung ohnehin mit 403 scheitern.
 */
/** Auswaehlbare Zeitraeume der Spaziergangs-Kachel. 30 ist die Backend-Obergrenze. */
export type WalkRangeDays = 7 | 14 | 30;

/** Eine Runde, fertig formatiert fuer die Klartext-Liste unter dem Diagramm. */
interface RecentWalk {
  day: string;
  timeRange: string;
  duration: string;
  distance: string;
}

const AXIS_COLOR = '#94a3b8';
const BAR_COLOR = '#aac7ff';

@Component({
  selector: 'app-tablet-toni',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-toni.component.html',
  styleUrl: './tablet-toni.component.scss'
})
export class TabletToniComponent implements OnInit, AfterViewInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly petSupplyService = inject(PetSupplyService);
  private readonly tractiveService = inject(TractiveService);
  private refreshTimer: number | null = null;

  /**
   * Der Kartencontainer steht IMMER im DOM, auch ohne Position - sonst haenge
   * der Aufbau der Karte an der Reihenfolge von Datenankunft und
   * Change-Detection, und der erste Abruf liefe ins Leere.
   */
  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private marker?: L.Marker;
  private viewReady = false;

  readonly walkRanges: readonly WalkRangeDays[] = [7, 14, 30];

  supplies: PetSupply[] = [];
  suppliesError: string | null = null;
  pet: TractivePet | null = null;
  petError: string | null = null;
  walks: TractiveWalk[] = [];
  walkError: string | null = null;
  walkDays: WalkRangeDays = 7;
  walkChartOptions: Record<string, unknown> = {};
  recentWalks: RecentWalk[] = [];

  ngOnInit(): void {
    this.rebuildWalkView();
    this.load(false);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletToniComponent.REFRESH_INTERVAL_MS
    );
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.renderMap();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
    this.map?.remove();
    this.map = undefined;
    this.marker = undefined;
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(true);
  }

  /**
   * Ton der ganzen Kachel: der SCHLECHTESTE der Vorraete. Beide teilen sich
   * eine Kachel, ein leerer Tablettenvorrat darf nicht hinter einem vollen
   * Futterlager verschwinden. Ohne Daten neutral.
   * Regel in shared/pet-supply-level.util.ts.
   */
  get suppliesTone(): PetSupplyTone {
    return worstPetSupplyTone(this.supplies);
  }

  /** Ton EINES Vorrats - faerbt seine eigene Zeile innerhalb der Kachel. */
  supplyTone(supply: PetSupply): PetSupplyTone {
    return petSupplyTone(supply);
  }

  barWidth(supply: PetSupply): number {
    return petSupplyBarWidth(supply.percent);
  }

  /** true, sobald eine verwertbare Position vorliegt. */
  get hasPosition(): boolean {
    return this.pet?.latitude != null && this.pet?.longitude != null;
  }

  setWalkDays(days: WalkRangeDays): void {
    if (days === this.walkDays) {
      return;
    }
    this.walkDays = days;
    this.rebuildWalkView();
    this.loadWalks(false);
  }

  /** Baut Diagramm und Klartext-Liste aus den gehaltenen Runden neu. */
  rebuildWalkView(): void {
    const now = new Date();
    const totals = walkDayTotals(this.walks, this.walkDays, now);

    this.walkChartOptions = {
      grid: { left: 48, right: 12, top: 10, bottom: 24, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: totals.map(total => total.label),
        axisLabel: { color: AXIS_COLOR, fontSize: 12 }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: AXIS_COLOR, fontSize: 12, formatter: '{value} min' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [{
        type: 'bar',
        data: totals.map(total => total.minutes),
        itemStyle: { color: BAR_COLOR, borderRadius: [4, 4, 0, 0] }
      }]
    };

    // Der Server liefert die neuesten Runden zuerst.
    this.recentWalks = this.walks.slice(0, 3).map(walk => ({
      day: walkDayLabel(walk.start, now),
      timeRange: walkTimeRange(walk),
      duration: walkDuration(walk),
      distance: walkDistance(walk)
    }));
  }

  /**
   * Die drei Quellen laufen unabhaengig: faellt Tractive aus, steht der
   * Futtervorrat trotzdem noch da - und umgekehrt.
   */
  private load(silent: boolean): void {
    this.loadSupplies(silent);
    this.loadPet(silent);
  }

  private loadSupplies(silent: boolean): void {
    this.petSupplyService.getSupplies().subscribe({
      next: supplies => {
        this.supplies = supplies;
        this.suppliesError = null;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Vorraete:', error);
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte nicht
        // durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer Wandanzeige
        // mehr wert als gar keine.
        if (!silent) {
          this.suppliesError = 'Vorräte nicht verfügbar.';
        }
      }
    });
  }

  private loadPet(silent: boolean): void {
    this.tractiveService.getPets().subscribe({
      next: pets => {
        // Bewusst das erste Tier: bei genau einem Hund ist das Toni. Mehrere Tiere
        // waeren eine eigene Entscheidung, keine stille Erweiterung des Rasters.
        this.pet = pets[0] ?? null;
        this.petError = null;
        this.loadWalks(silent);
        this.renderMap();
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Trackers:', error);
        if (!silent) {
          this.petError = 'Tracker nicht verfügbar.';
        }
      }
    });
  }

  // Bewusst ohne switchMap/Request-Guard: ein langsamer Abruf koennte theoretisch
  // nach einem schnelleren eintreffen und veraltete Runden schreiben. Auf einer
  // Wandanzeige mit seltener Interaktion und 5-Minuten-Refresh ist die Folge
  // harmlos und selbstheilend.
  private loadWalks(silent: boolean): void {
    const trackerId = this.pet?.trackerId;
    if (!trackerId) {
      return;
    }
    this.tractiveService.getWalks(trackerId, this.walkDays).subscribe({
      next: walks => {
        this.walks = walks;
        this.walkError = null;
        this.rebuildWalkView();
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Spaziergänge:', error);
        if (!silent) {
          // Der haeufigste Grund ist das Rate-Limit der Tractive-Cloud.
          this.walkError = 'Spaziergänge nicht verfügbar.';
        }
      }
    });
  }

  /**
   * Baut die Karte beim ersten Datensatz auf und verschiebt danach nur den
   * Marker - ein Neuaufbau bei jedem Refresh wuerde die Kacheln neu laden.
   */
  private renderMap(): void {
    if (!this.viewReady || !this.hasPosition) {
      return;
    }
    const container = this.mapContainer?.nativeElement;
    if (!container) {
      return;
    }
    const position: L.LatLngExpression = [this.pet!.latitude!, this.pet!.longitude!];
    if (!this.map) {
      this.map = L.map(container).setView(position, 16);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
      }).addTo(this.map);
    }
    if (this.marker) {
      this.marker.setLatLng(position);
    } else {
      this.marker = L.marker(position).addTo(this.map).bindPopup(this.pet!.name);
    }
    this.map.setView(position);
  }
}
