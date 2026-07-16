import { Component, HostListener, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription, interval, of, startWith, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { ViewModeService } from '../../services/view-mode.service';
import { TemperatureService } from '../../services/temperature.service';
import { EnergyLive } from '../../models/energy-live.model';
import { AnkerSolixLive } from '../../models/ankersolix.model';
import { WeatherOverview } from '../../models/weather.model';
import { CurrentTemperatureReading } from '../../models/temperature.model';
import { weatherSymbol } from '../../shared/weather-icon.util';
import { ClimateView, buildClimateView } from '../../shared/temperature-comfort.util';
import { EnergyFlowComponent } from '../../components/energy-flow/energy-flow.component';
import { WasteCollectionTileComponent } from '../../components/waste-collection-tile/waste-collection-tile.component';
import { SwitchService } from '../../services/switch.service';
import { SwitchEntity } from '../../models/switch.model';
import { SwitchListComponent } from '../../components/switch-list/switch-list.component';

/**
 * Dashboard component - "Lumina" Wand-Dashboard.
 * Vollflaechige Kommandozentrale im Kiosk-Stil: grosse Uhr, Wetter, Raum-Kacheln,
 * Szenen, Intelligence Hub, Live-Energie-Ring und Modus-Schnellaktionen.
 *
 * Echte Daten: Uhr, Wetter (WeatherService), Live-Energie (EnergyLiveService).
 * Raeume, Szenen, Intelligence-Hinweise, Sicherheit und Modi sind aktuell statische
 * Platzhalter, aber als typisierte Datenstrukturen fuer eine spaetere Anbindung angelegt.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, EnergyFlowComponent, WasteCollectionTileComponent, SwitchListComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly weatherService = inject(WeatherService);
  private readonly energyLiveService = inject(EnergyLiveService);
  private readonly ankerSolixService = inject(AnkerSolixService);
  private readonly temperatureService = inject(TemperatureService);
  private readonly switchService = inject(SwitchService);

  /** Umschalter zwischen Website- und Tablet-Ansicht (blendet den Header aus). */
  readonly viewMode = inject(ViewModeService);

  private clockSubscription?: Subscription;
  private weatherSubscription?: Subscription;
  private liveSubscription?: Subscription;
  private statusSubscription?: Subscription;
  private temperatureSubscription?: Subscription;
  private ankerSubscription?: Subscription;
  private switchSubscription?: Subscription;

  /** Umfang des SVG-Rings (r = 40 -> 2*pi*40). */
  private static readonly RING_CIRCUMFERENCE = 251.2;
  /** Hausverbrauch, der den Verbrauchs-Ring komplett fuellt (5 kW). */
  private static readonly CONSUMPTION_MAX_WATT = 5000;
  /** PV-Erzeugung, die den PV-Ring komplett fuellt (2 kW). */
  private static readonly PV_MAX_WATT = 2000;
  /** Netzbezug, der den Bezugs-Ring komplett fuellt (5 kW). */
  private static readonly GRID_MAX_WATT = 5000;
  /** Aktualisierungsintervall der Klima-Kachel (60 s). */
  private static readonly CLIMATE_REFRESH_MS = 60000;
  /** Anzahl der Schalter auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly SWITCH_TILE_LIMIT = 4;
  /** Aktualisierungsintervall der Schalter-Kachel (30 s). */
  private static readonly SWITCH_REFRESH_MS = 30000;

  /** Aktuelle Uhrzeit als Date, sekuendlich aktualisiert. */
  now = new Date();

  weather: WeatherOverview | null = null;
  weatherError = false;

  energyLive: EnergyLive | null = null;
  liveStatus: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';

  /** Anker-Speicher-Live-Werte fuer den Energiefluss-Dialog (nur waehrend geoeffnet aktiv). */
  ankerLive: AnkerSolixLive | null = null;
  /** True, wenn der Energiefluss-Dialog geoeffnet ist. */
  flowDialogOpen = false;

  climate: ClimateView = { outdoor: [], weatherLabel: '--', rows: [] };

  /** Meistgenutzte Schalter fuer die Kachel. */
  topSwitches: SwitchEntity[] = [];
  /** Alle Schalter; nur geladen, solange der Schalter-Dialog offen ist. */
  allSwitches: SwitchEntity[] = [];
  /** True, wenn der Schalter-Dialog geoeffnet ist. */
  switchDialogOpen = false;
  /** Entity-IDs mit laufendem Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingSwitchIds = new Set<string>();
  switchError: string | null = null;

  /** Raum-Kacheln (Platzhalter, spaeter aus Entitaeten befuellbar). */
  readonly rooms: RoomTile[] = [
    { name: 'Schlafzimmer', icon: 'bed', status: 'Ruhe', tone: 'idle', detail: 'Luftreiniger: An • Rollo: Zu' }
  ];

  /** Aktive Szene und Schnellwahl-Szenen (Platzhalter). */
  activeScene = 'Dynamisches Abendlicht';
  readonly scenes: SceneButton[] = [
    { label: 'Filmabend', active: true },
    { label: 'Lesen', active: false }
  ];

  /** Intelligence-Hub-Hinweise (Platzhalter). */
  readonly insights: IntelligenceItem[] = [
    {
      icon: 'lightbulb',
      tone: 'primary',
      title: 'Energie-Optimierung',
      text: 'Wohnzimmer um 1 °C senken könnte heute 5 % sparen.'
    },
    {
      icon: 'package_2',
      tone: 'secondary',
      title: 'Lieferung',
      text: 'Paket vor 12 Minuten an der Haustür erkannt.'
    },
    {
      icon: 'schedule',
      tone: 'muted',
      title: 'Routine geplant',
      text: 'Schlafmodus aktiviert sich in 45 Minuten.'
    }
  ];

  /** Modus-Schnellaktionen (Platzhalter, aktuell ohne Funktion). */
  readonly modes: ModeButton[] = [
    { label: 'Abwesend', icon: 'exit_to_app', tone: 'primary' },
    { label: 'Party', icon: 'celebration', tone: 'tertiary' },
    { label: 'Gute Nacht', icon: 'nights_stay', tone: 'neutral' },
    { label: 'Ausschalten', icon: 'power_settings_new', tone: 'error' }
  ];

  ngOnInit(): void {
    this.startClock();
    this.loadWeather();
    this.startLiveStream();
    this.startClimateRefresh();
    this.startSwitchRefresh();
  }

  ngOnDestroy(): void {
    this.clockSubscription?.unsubscribe();
    this.weatherSubscription?.unsubscribe();
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.temperatureSubscription?.unsubscribe();
    this.switchSubscription?.unsubscribe();
    this.closeFlowDialog();
    this.energyLiveService.disconnect();
  }

  /**
   * Oeffnet den Energiefluss-Dialog und abonniert dafuer den Anker-Live-Stream.
   * Der EnergyLive-Stream laeuft bereits fuer die Gauges und wird an den Dialog
   * durchgereicht, sodass pro Seite nur ein Abonnent je Live-Service existiert.
   */
  openFlowDialog(): void {
    if (this.flowDialogOpen) {
      return;
    }
    this.flowDialogOpen = true;
    this.ankerSubscription = this.ankerSolixService.getLiveStream().subscribe({
      next: reading => (this.ankerLive = reading),
      error: err => console.error('Anker SSE-Fehler:', err)
    });
  }

  /** Schliesst die geoeffneten Dialoge per Escape-Taste. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeFlowDialog();
    this.closeSwitchDialog();
  }

  /** Schliesst den Dialog und trennt den nur dafuer benoetigten Anker-Live-Stream. */
  closeFlowDialog(): void {
    if (!this.flowDialogOpen) {
      return;
    }
    this.flowDialogOpen = false;
    this.ankerSubscription?.unsubscribe();
    this.ankerSubscription = undefined;
    this.ankerSolixService.disconnectLive();
    this.ankerLive = null;
  }

  /**
   * Schaltet einen Schalter direkt. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt, damit die Kachel sofort reagiert.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    const previousState = entity.state;
    this.pendingSwitchIds.add(entity.entityId);
    this.switchError = null;
    this.applySwitchState(entity.entityId, entity.state === 'on' ? 'off' : 'on');

    this.switchService.toggle(entity.entityId).subscribe({
      next: updated => {
        this.pendingSwitchIds.delete(entity.entityId);
        this.applySwitchState(updated.entityId, updated.state);
      },
      error: () => {
        this.pendingSwitchIds.delete(entity.entityId);
        this.applySwitchState(entity.entityId, previousState);
        this.switchError = `${entity.displayName} konnte nicht geschaltet werden.`;
      }
    });
  }

  /**
   * Setzt den Zustand in Kachel- und Dialogliste. Die Zuordnung laeuft ueber die
   * entityId, damit sie auch nach einem zwischenzeitlichen Neuladen greift.
   */
  private applySwitchState(entityId: string, state: string): void {
    for (const list of [this.topSwitches, this.allSwitches]) {
      const match = list.find(item => item.entityId === entityId);
      if (match) {
        match.state = state;
      }
    }
  }

  /** Oeffnet den Schalter-Dialog und laedt dafuer die vollstaendige Liste. */
  openSwitchDialog(): void {
    if (this.switchDialogOpen) {
      return;
    }
    this.switchDialogOpen = true;
    this.switchService.getSwitches().subscribe({
      next: switches => (this.allSwitches = switches),
      error: () => (this.switchError = 'Schalter konnten nicht geladen werden.')
    });
  }

  /** Schliesst den Dialog und laedt die Kachel neu (die Reihenfolge kann sich geaendert haben). */
  closeSwitchDialog(): void {
    if (!this.switchDialogOpen) {
      return;
    }
    this.switchDialogOpen = false;
    this.allSwitches = [];
    this.switchError = null;
    this.loadTopSwitches();
  }

  /** Uhrzeit im Format HH:MM (24h). */
  get clockTime(): string {
    return this.now.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  }

  /** Wochentag ausgeschrieben, z. B. "Montag". */
  get weekday(): string {
    return this.now.toLocaleDateString('de-DE', { weekday: 'long' });
  }

  /** Datum, z. B. "14. Juli". */
  get calendarDate(): string {
    return this.now.toLocaleDateString('de-DE', { day: 'numeric', month: 'long' });
  }

  /** Aktuelle Aussentemperatur, gerundet auf Ganzzahl. */
  get temperature(): string {
    const value = this.weather?.current?.temperature;
    if (value == null) {
      return '--';
    }
    return `${Math.round(value)}`;
  }

  /** Kurzbeschreibung des Wetters, z. B. "Wolkig". */
  get weatherLabel(): string {
    if (this.weatherError) {
      return 'Nicht verfügbar';
    }
    return weatherSymbol(this.weather?.current?.icon).label;
  }

  /** Material-Symbol passend zum aktuellen Wetter. */
  get weatherIcon(): string {
    return this.weatherMaterialSymbol(this.weather?.current?.icon);
  }

  get ringCircumference(): number {
    return DashboardComponent.RING_CIRCUMFERENCE;
  }

  /**
   * Drei Live-Gauges des Energieflusses: PV-Erzeugung, Hausverbrauch und Netzbezug.
   * Jeder Gauge liefert Anzeigewert (kW), Ring-Offset und Farbton fuer das Template.
   */
  get energyGauges(): EnergyGauge[] {
    const live = this.energyLive;
    const pvWatt = live?.pvTotalW ?? 0;
    const consumptionWatt = live?.hausverbrauchW ?? 0;
    const gridWatt = Math.abs(live?.gridW ?? 0);
    // Einspeisung: Netz exportiert (nicht importierend) mit tatsaechlichem Fluss.
    const exporting = !!live && !live.gridImporting && gridWatt > 0;

    return [
      {
        key: 'pv',
        tone: 'pv',
        label: 'PV-Erzeugung',
        icon: 'solar_power',
        value: this.formatWatt(live ? pvWatt : null),
        offset: this.gaugeOffset(pvWatt, DashboardComponent.PV_MAX_WATT)
      },
      {
        key: 'consumption',
        tone: 'consumption',
        label: 'Verbrauch',
        icon: 'bolt',
        value: this.formatWatt(live ? consumptionWatt : null),
        offset: this.gaugeOffset(consumptionWatt, DashboardComponent.CONSUMPTION_MAX_WATT)
      },
      {
        key: 'grid',
        tone: exporting ? 'export' : 'grid',
        label: exporting ? 'Einspeisung' : 'Bezug',
        icon: exporting ? 'solar_power' : 'power',
        value: this.formatWatt(live ? gridWatt : null),
        offset: this.gaugeOffset(gridWatt, DashboardComponent.GRID_MAX_WATT)
      }
    ];
  }

  /** Ring-Offset fuer einen Wert relativ zu seinem Maximum (voller Ring = 0). */
  private gaugeOffset(watt: number, maxWatt: number): number {
    const fraction = Math.min(Math.max(watt / maxWatt, 0), 1);
    return DashboardComponent.RING_CIRCUMFERENCE * (1 - fraction);
  }

  /** Formatiert Watt als ganzzahligen W-String mit Tausendertrennung ("--" bei fehlenden Daten). */
  private formatWatt(watt: number | null): string {
    if (watt == null) {
      return '--';
    }
    return Math.round(watt).toLocaleString('de-DE', { maximumFractionDigits: 0 });
  }

  /** True, wenn der Live-Energie-Stream verbunden ist. */
  get energyConnected(): boolean {
    return this.liveStatus === 'connected';
  }

  /** Betrag des Netzflusses in Watt (Bezug oder Einspeisung). */
  get gridAbsW(): number {
    return this.energyLive ? Math.abs(this.energyLive.gridW) : 0;
  }

  /** Beschriftung der Netz-Kennzahl je nach Flussrichtung. */
  get gridFlowLabel(): string {
    if (!this.energyLive || this.gridAbsW === 0) {
      return 'Netz';
    }
    return this.energyLive.gridImporting ? 'Netzbezug' : 'Einspeisung';
  }

  /** Farbton der Netz-Kennzahl: Bezug (schlecht) vs. Einspeisung (gut). */
  get gridFlowTone(): 'import' | 'export' | 'neutral' {
    if (!this.energyLive || this.gridAbsW === 0) {
      return 'neutral';
    }
    return this.energyLive.gridImporting ? 'import' : 'export';
  }

  private startClock(): void {
    this.clockSubscription = interval(1000)
      .pipe(startWith(0))
      .subscribe(() => (this.now = new Date()));
  }

  private loadWeather(): void {
    this.weatherError = false;
    this.weatherSubscription = this.weatherService.getOverview().subscribe({
      next: overview => {
        this.weather = overview;
        this.weatherError = false;
      },
      error: () => {
        this.weatherError = true;
      }
    });
  }

  private startLiveStream(): void {
    this.liveSubscription = this.energyLiveService.getLiveStream().subscribe({
      next: reading => (this.energyLive = reading),
      error: () => (this.liveStatus = 'error')
    });
    this.statusSubscription = this.energyLiveService.getStatusStream().subscribe({
      next: status => (this.liveStatus = status)
    });
  }

  private startClimateRefresh(): void {
    this.temperatureSubscription = interval(DashboardComponent.CLIMATE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.temperatureService.getCurrent().pipe(
            catchError(() => of<CurrentTemperatureReading[]>([]))
          )
        )
      )
      .subscribe(readings => (this.climate = buildClimateView(readings, Date.now())));
  }

  private startSwitchRefresh(): void {
    this.switchSubscription = interval(DashboardComponent.SWITCH_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.topSwitchRequest())
      )
      .subscribe(switches => {
        this.topSwitches = switches;
        // Der Hinweis gehoert zum letzten Schaltversuch: mit frischen Daten ist er ueberholt.
        this.switchError = null;
      });
  }

  private loadTopSwitches(): void {
    this.topSwitchRequest().subscribe(switches => (this.topSwitches = switches));
  }

  private topSwitchRequest() {
    return this.switchService.getSwitches(DashboardComponent.SWITCH_TILE_LIMIT).pipe(
      catchError(() => of<SwitchEntity[]>([]))
    );
  }

  /** Mappt DWD-Icon-Codes auf Material-Symbols-Namen fuer die Wetteranzeige. */
  private weatherMaterialSymbol(icon: number | null | undefined): string {
    switch (icon) {
      case 1:
        return 'sunny';
      case 2:
      case 3:
        return 'partly_cloudy_day';
      case 4:
        return 'cloud';
      case 5:
      case 6:
        return 'foggy';
      case 7:
      case 8:
      case 9:
      case 10:
      case 14:
      case 15:
        return 'rainy';
      case 11:
      case 12:
      case 13:
        return 'weather_snowy';
      case 16:
        return 'thunderstorm';
      default:
        return 'device_thermostat';
    }
  }
}

/** Raum-Kachel im Dashboard. */
interface RoomTile {
  readonly name: string;
  readonly icon: string;
  readonly status: string;
  readonly tone: 'active' | 'idle';
  readonly detail: string;
}

/** Szenen-Schnellwahl-Button. */
interface SceneButton {
  readonly label: string;
  readonly active: boolean;
}

/** Hinweis-Karte im Intelligence Hub. */
interface IntelligenceItem {
  readonly icon: string;
  readonly tone: 'primary' | 'secondary' | 'muted';
  readonly title: string;
  readonly text: string;
}

/** Live-Gauge des Energieflusses (PV, Verbrauch, Bezug/Einspeisung). */
interface EnergyGauge {
  readonly key: 'pv' | 'consumption' | 'grid';
  /** Farbton-Modifier: 'grid' = Bezug (rot), 'export' = Einspeisung (gruen). */
  readonly tone: 'pv' | 'consumption' | 'grid' | 'export';
  readonly label: string;
  readonly icon: string;
  readonly value: string;
  readonly offset: number;
}

/** Modus-Schnellaktion in der Fussleiste. */
interface ModeButton {
  readonly label: string;
  readonly icon: string;
  readonly tone: 'primary' | 'tertiary' | 'neutral' | 'error';
}
