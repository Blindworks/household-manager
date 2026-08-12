import { Component, HostListener, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription, interval, merge, of, startWith, switchMap, timer } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { ViewModeService } from '../../services/view-mode.service';
import {
  DashboardAccordionService,
  DashboardTileKey
} from '../../services/dashboard-accordion.service';
import { TemperatureService } from '../../services/temperature.service';
import { EnergyLive } from '../../models/energy-live.model';
import { AnkerSolixLive } from '../../models/ankersolix.model';
import { WeatherOverview } from '../../models/weather.model';
import { CurrentTemperatureReading, TemperatureSensorSeries, TimeRange } from '../../models/temperature.model';
import { weatherSymbol } from '../../shared/weather-icon.util';
import {
  ClimateView,
  SensorDetail,
  buildClimateView,
  buildSensorDetail
} from '../../shared/temperature-comfort.util';
import { EnergyFlowComponent } from '../../components/energy-flow/energy-flow.component';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { buildWasteInsight } from '../../shared/waste-insight.util';
import { CalendarService } from '../../services/calendar.service';
import { buildCalendarInsights } from '../../shared/calendar-insight.util';
import { InsightService } from '../../services/insight.service';
import { buildVentilationInsight } from '../../shared/ventilation-insight.util';
import { buildTrackerBatteryInsight } from '../../shared/battery-insight.util';
import { VentilationAssessment } from '../../models/ventilation.model';
import { HubInsight } from '../../shared/hub-insight.model';
import { SwitchService } from '../../services/switch.service';
import { SwitchEntity } from '../../models/switch.model';
import { ModeService } from '../../services/mode.service';
import { ModeEntity } from '../../models/mode.model';
import { SwitchListComponent } from '../../components/switch-list/switch-list.component';
import { NukiService } from '../../services/nuki.service';
import { NukiLock, NukiLockActionType } from '../../models/nuki.model';
import { PowerConsumerService } from '../../services/power-consumer.service';
import { PowerConsumer, PowerHistory, PowerRange } from '../../models/power-consumer.model';
import { TractiveService } from '../../services/tractive.service';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeHealth } from '../../models/zigbee.model';

// LegendComponent: ohne Legende ist bei zwei Linien nicht erkennbar, welche welche ist.
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

/**
 * Dashboard component - "Lumina" Wand-Dashboard.
 * Vollflaechige Kommandozentrale im Kiosk-Stil: grosse Uhr, Wetter, Kacheln fuer
 * Klima, Schalter und Verbraucher, Szenen, Intelligence Hub, Live-Energie-Ring
 * und Modus-Schnellaktionen.
 *
 * Echte Daten: Uhr, Wetter (WeatherService), Live-Energie (EnergyLiveService),
 * Klima, Schalter, Verbraucher, Modi, Müllabfuhr und Türschloss.
 * Szenen und Intelligence-Hinweise sind aktuell statische Platzhalter.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, EnergyFlowComponent, SwitchListComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly weatherService = inject(WeatherService);
  private readonly energyLiveService = inject(EnergyLiveService);
  private readonly ankerSolixService = inject(AnkerSolixService);
  private readonly temperatureService = inject(TemperatureService);
  private readonly insightService = inject(InsightService);
  private readonly switchService = inject(SwitchService);
  private readonly modeService = inject(ModeService);
  private readonly wasteService = inject(WasteCollectionService);
  private readonly calendarService = inject(CalendarService);
  private readonly nukiService = inject(NukiService);
  private readonly powerConsumerService = inject(PowerConsumerService);
  private readonly tractiveService = inject(TractiveService);
  private readonly zigbeeService = inject(ZigbeeService);

  /** Umschalter zwischen Website- und Tablet-Ansicht (blendet den Header aus). */
  readonly viewMode = inject(ViewModeService);

  private readonly tileAccordion = inject(DashboardAccordionService);

  /**
   * True, wenn die Kacheln Temperaturen/Schalter/Verbraucher als Akkordeon
   * untereinander stehen. Nur in der Tablet-Ansicht – in der Website-Ansicht
   * bleibt das dreispaltige Raster.
   */
  get accordionActive(): boolean {
    return this.viewMode.isTabletView();
  }

  /** True, wenn der Inhalt der Kachel sichtbar ist (ausserhalb des Akkordeons immer). */
  isTileOpen(key: DashboardTileKey): boolean {
    return !this.accordionActive || this.tileAccordion.isOpen(key);
  }

  toggleTile(key: DashboardTileKey): void {
    this.tileAccordion.toggle(key);
  }

  private clockSubscription?: Subscription;
  private weatherSubscription?: Subscription;
  private liveSubscription?: Subscription;
  private statusSubscription?: Subscription;
  private temperatureSubscription?: Subscription;
  private ventilationSubscription?: Subscription;
  private ankerSubscription?: Subscription;
  private switchSubscription?: Subscription;
  private modeSubscription?: Subscription;
  private wasteSubscription?: Subscription;
  private calendarSubscription?: Subscription;
  private nukiSubscription?: Subscription;
  private consumerSubscription?: Subscription;
  private petSubscription?: Subscription;
  private zigbeeHealthSubscription?: Subscription;

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
  /** Aktualisierungsintervall der Modus-Leiste (30 s; Flows schalten Modi auch von aussen). */
  private static readonly MODE_REFRESH_MS = 30000;
  /** Farbton je Modus-Position (bestehende lumina__mode-Varianten). */
  private static readonly MODE_TONES = ['primary', 'tertiary', 'neutral', 'error'] as const;
  /** Haelt die Muell-Meldung ueber den Tag hinweg aktuell (z. B. bei geaenderten Einstellungen). */
  private static readonly WASTE_REFRESH_MS = 3600000;
  /** Kalender-Hub-Eintraege alle 5 Minuten auffrischen (Termine aendern sich haeufiger als Muell). */
  private static readonly CALENDAR_REFRESH_MS = 300000;
  private static readonly DAY_MS = 86400000;
  /** Aktualisierungsintervall der Türschloss-Kachel (30 s). */
  private static readonly NUKI_REFRESH_MS = 30000;
  /** Anzahl der Verbraucher auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly CONSUMER_TILE_LIMIT = 4;
  /** Aktualisierungsintervall der Verbraucher-Kachel (30 s). */
  private static readonly CONSUMER_REFRESH_MS = 30000;
  private static readonly PETS_REFRESH_MS = 60000;
  /** Aktualisierungsintervall der Zigbee-Health-Kachel (60 s; das Wandtablet laedt die Seite nur einmal). */
  private static readonly ZIGBEE_HEALTH_REFRESH_MS = 60000;

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

  /** Zuletzt geladene Rohmesswerte; Quelle des Sensor-Detaildialogs. */
  private currentTemperatures: CurrentTemperatureReading[] = [];
  /** Sensor, dessen Detailwerte gerade angezeigt werden (null = Dialog zu). */
  sensorDetail: SensorDetail | null = null;
  /** Gewählter Zeitraum des Sensor-Verlaufs. */
  sensorHistoryRange: TimeRange = 'DAY';
  /** ECharts-Optionen des Sensor-Verlaufs. */
  sensorHistoryOptions: Record<string, unknown> | null = null;
  /** True, wenn im gewählten Zeitraum keine Messpunkte vorliegen. */
  sensorHistoryEmpty = false;
  sensorHistoryError: string | null = null;

  /** Auswählbare Zeiträume des Sensor-Verlaufs. */
  readonly sensorHistoryRanges: { value: TimeRange; label: string }[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  /** Meistgenutzte Schalter fuer die Kachel. */
  topSwitches: SwitchEntity[] = [];
  /** Alle Schalter; nur geladen, solange der Schalter-Dialog offen ist. */
  allSwitches: SwitchEntity[] = [];
  /** True, wenn der Schalter-Dialog geoeffnet ist. */
  switchDialogOpen = false;
  /** Entity-IDs mit laufendem Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingSwitchIds = new Set<string>();
  switchError: string | null = null;
  /** Entität, deren Schalten gerade auf Bestätigung wartet (null = Dialog zu). */
  confirmSwitch: SwitchEntity | null = null;
  /** Liste mit genau dem zu bestätigenden Schalter für app-switch-list. */
  confirmSwitchList: SwitchEntity[] = [];

  /** Größte Stromverbraucher für die Kachel. */
  topConsumers: PowerConsumer[] = [];
  /** Alle Verbraucher; nur gefüllt, solange der Verbraucher-Dialog offen ist. */
  allConsumers: PowerConsumer[] = [];
  /** True, wenn der Verbraucher-Dialog geöffnet ist. */
  consumerDialogOpen = false;

  /** Verbraucher, dessen Verlauf gerade angezeigt wird (null = Dialog zu). */
  historyConsumer: PowerConsumer | null = null;
  /** Gewählter Zeitraum des Verlaufs. */
  historyRange: PowerRange = 'DAY';
  /** ECharts-Optionen des Verlaufs. */
  historyOptions: Record<string, unknown> | null = null;
  /** True, wenn für den Zeitraum noch keine Messpunkte vorliegen. */
  historyEmpty = false;
  historyError: string | null = null;

  /** Auswählbare Zeiträume des Verlaufs. */
  readonly historyRanges: { value: PowerRange; label: string }[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  /** Aktive Szene und Schnellwahl-Szenen (Platzhalter). */
  activeScene = 'Dynamisches Abendlicht';
  readonly scenes: SceneButton[] = [
    { label: 'Filmabend', active: true },
    { label: 'Lesen', active: false }
  ];

  /**
   * Hinweise des Intelligence Hub: die Muellabfuhr voran (sofern etwas ansteht),
   * dahinter die Platzhalter. Wird von {@link startWasteRefresh} neu gesetzt.
   */
  insights: IntelligenceItem[] = [];

  /** Zuletzt gebaute Muell-Meldung; null = nichts ansteht. */
  private wasteInsight: HubInsight | null = null;
  /** Zuletzt gebaute Kalender-Eintraege (max. 3). */
  private calendarInsights: HubInsight[] = [];
  /** Zuletzt gebaute Lueftungs-Karte; null = keine Empfehlung. */
  private ventilationInsight: HubInsight | null = null;
  /** Zuletzt gebaute Tracker-Akku-Warnung; null = alle Akkus ausreichend. */
  private trackerBatteryInsight: HubInsight | null = null;

  /** Noch nicht angebundene Hub-Hinweise (Platzhalter). */
  private static readonly PLACEHOLDER_INSIGHTS: IntelligenceItem[] = [
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

  /** Haus-Modi der Fussleiste, vom Backend geladen. */
  modes: ModeEntity[] = [];
  /** Entity-IDs mit laufendem Modus-Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingModeIds = new Set<string>();
  modeError: string | null = null;

  /** Nuki-Schlösser für die Türschloss-Kachel. */
  nukiLocks: NukiLock[] = [];
  /** True, solange noch keine Nuki-Antwort vorliegt (unterscheidet "lädt" von "keine Schlösser"). */
  nukiLoading = true;
  nukiError: string | null = null;
  /** Smartlock-IDs mit laufender Aktion (verhindert Doppelklicks). */
  readonly pendingNukiIds = new Set<number>();
  /** Zu bestätigende Aktion (Entsperren/Tür öffnen); null = kein Dialog offen. */
  nukiConfirm: { lock: NukiLock; action: NukiLockActionType } | null = null;

  /** Haustiere fuer die Zu-Hause-Kachel; leer = Kachel wird nicht gerendert. */
  pets: TractivePet[] = [];
  /** True, solange der Spaziergänge-Dialog offen ist. */
  walksDialogOpen = false;
  /** Ein Abschnitt pro Hund im Spaziergänge-Dialog. */
  walkSections: PetWalkSection[] = [];
  /** Verwirft verspätete Antworten eines bereits geschlossenen Dialogs. */
  private walksRequestId = 0;

  /** Zustand der Zigbee-Anbindung; null = noch kein Ergebnis (vor dem ersten Abruf). */
  zigbeeHealth: ZigbeeHealth | null = null;

  ngOnInit(): void {
    this.insights = [...DashboardComponent.PLACEHOLDER_INSIGHTS];
    this.startClock();
    this.loadWeather();
    this.startLiveStream();
    this.startClimateRefresh();
    this.startSwitchRefresh();
    this.startConsumerRefresh();
    this.startModeRefresh();
    this.startWasteRefresh();
    this.startCalendarRefresh();
    this.startVentilationRefresh();
    this.startNukiRefresh();
    this.startPetRefresh();
    this.startZigbeeHealthRefresh();
  }

  ngOnDestroy(): void {
    this.clockSubscription?.unsubscribe();
    this.weatherSubscription?.unsubscribe();
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.temperatureSubscription?.unsubscribe();
    this.ventilationSubscription?.unsubscribe();
    this.switchSubscription?.unsubscribe();
    this.modeSubscription?.unsubscribe();
    this.wasteSubscription?.unsubscribe();
    this.calendarSubscription?.unsubscribe();
    this.nukiSubscription?.unsubscribe();
    this.consumerSubscription?.unsubscribe();
    this.petSubscription?.unsubscribe();
    this.zigbeeHealthSubscription?.unsubscribe();
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
    if (this.walksDialogOpen) {
      this.closeWalksDialog();
      return;
    }
    if (this.historyConsumer) {
      this.closeHistoryDialog();
      return;
    }
    if (this.sensorDetail) {
      this.closeSensorDialog();
      return;
    }
    this.closeFlowDialog();
    this.closeSwitchDialog();
    this.closeConfirmDialog();
    this.closeConsumerDialog();
    this.nukiConfirm = null;
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
   * Schaltet einen Schalter. Bestätigungspflichtige Schalter werden nicht direkt
   * geschaltet, sondern öffnen den Bestätigungsdialog; erst der Klick auf den
   * Schalter im Dialog führt den Toggle aus.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    if (entity.confirmRequired) {
      this.confirmSwitch = entity;
      this.confirmSwitchList = [entity];
      return;
    }
    this.executeToggle(entity);
  }

  /** Bestätigung im Dialog: schließt ihn und führt den eigentlichen Toggle aus. */
  confirmToggle(entity: SwitchEntity): void {
    this.closeConfirmDialog();
    this.executeToggle(entity);
  }

  closeConfirmDialog(): void {
    this.confirmSwitch = null;
    this.confirmSwitchList = [];
  }

  /**
   * Führt den Schaltbefehl aus. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt, damit die Kachel sofort reagiert.
   */
  private executeToggle(entity: SwitchEntity): void {
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

  /** Farbton eines Modus-Knopfs anhand seiner Position; ueberzaehlige werden neutral. */
  modeTone(index: number): string {
    return DashboardComponent.MODE_TONES[index] ?? 'neutral';
  }

  /**
   * Schaltet einen Haus-Modus. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt (gleiches Muster wie {@link toggleSwitch}).
   */
  toggleMode(mode: ModeEntity): void {
    if (this.pendingModeIds.has(mode.entityId)) {
      return;
    }
    const previousState = mode.state;
    this.pendingModeIds.add(mode.entityId);
    this.modeError = null;
    this.applyModeState(mode.entityId, previousState === 'on' ? 'off' : 'on');

    this.modeService.toggle(mode.entityId).subscribe({
      next: updated => {
        this.pendingModeIds.delete(mode.entityId);
        this.applyModeState(updated.entityId, updated.state);
      },
      error: () => {
        this.pendingModeIds.delete(mode.entityId);
        this.applyModeState(mode.entityId, previousState);
        this.modeError = `${mode.displayName} konnte nicht geschaltet werden.`;
      }
    });
  }

  private applyModeState(entityId: string, state: string): void {
    const match = this.modes.find(item => item.entityId === entityId);
    if (match) {
      match.state = state;
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

  /** Öffnet den Detaildialog eines Temperatursensors (Temperatur + Luftfeuchte). */
  openSensorDialog(sensorId: string): void {
    this.sensorDetail = buildSensorDetail(this.currentTemperatures, sensorId, Date.now());
    this.sensorHistoryRange = 'DAY';
    this.sensorHistoryOptions = null;
    this.sensorHistoryEmpty = false;
    this.sensorHistoryError = null;
    if (this.sensorDetail) {
      this.loadSensorHistory();
    }
  }

  closeSensorDialog(): void {
    this.sensorDetail = null;
    this.sensorHistoryRange = 'DAY';
    this.sensorHistoryOptions = null;
    this.sensorHistoryEmpty = false;
    this.sensorHistoryError = null;
  }

  setSensorHistoryRange(range: TimeRange): void {
    if (range === this.sensorHistoryRange) {
      return;
    }
    this.sensorHistoryRange = range;
    this.sensorHistoryOptions = null;
    this.loadSensorHistory();
  }

  private loadSensorHistory(): void {
    const detail = this.sensorDetail;
    if (!detail) {
      return;
    }
    const requestedId = detail.sensorId;
    const requestedRange = this.sensorHistoryRange;
    this.sensorHistoryError = null;
    this.sensorHistoryEmpty = false;
    this.temperatureService.getSensorSeries(requestedId, requestedRange).subscribe({
      next: series => {
        // Verworfen, wenn der Dialog inzwischen geschlossen, auf einen anderen Sensor
        // gewechselt oder auf einen anderen Zeitraum gestellt wurde: sonst ueberschreibt
        // eine spaet eintreffende 30-Tage-Antwort die schon geladene 24-Stunden-Sicht.
        if (this.sensorDetail?.sensorId !== requestedId
            || this.sensorHistoryRange !== requestedRange) {
          return;
        }
        this.sensorHistoryEmpty = series.temperature.length === 0;
        this.sensorHistoryOptions = this.buildSensorHistoryOptions(series);
      },
      error: () => {
        // Derselbe Schutz: ein fehlgeschlagener alter Request darf nicht die Fehlermeldung
        // ueber einen inzwischen erfolgreich geladenen Verlauf legen.
        if (this.sensorDetail?.sensorId !== requestedId
            || this.sensorHistoryRange !== requestedRange) {
          return;
        }
        this.sensorHistoryOptions = null;
        this.sensorHistoryError = 'Verlauf konnte nicht geladen werden.';
      }
    });
  }

  /**
   * Liniendiagramm des Sensor-Verlaufs. Temperatur links, Luftfeuchte rechts auf eigener
   * Achse. Fehlen Feuchtewerte, entfallen Serie und rechte Achse: eine leere zweite Achse
   * suggeriert fehlende Daten, wo es nie welche gab.
   *
   * Kein connectNulls-Abriss wie beim Leistungsverlauf — Temperatursensoren melden nur bei
   * Wertaenderung, eine Funkpause ist dort der Normalfall und kein Messausfall.
   */
  private buildSensorHistoryOptions(series: TemperatureSensorSeries): Record<string, unknown> {
    const hasHumidity = series.humidity.length > 0;
    const yAxis: Record<string, unknown>[] = [
      {
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} °C' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      }
    ];
    const chartSeries: Record<string, unknown>[] = [
      {
        name: 'Temperatur',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: series.temperature.map(point => [point.time, point.value]),
        lineStyle: { width: 2.5, color: '#ef4444' },
        itemStyle: { color: '#ef4444' },
        areaStyle: { color: 'rgba(239, 68, 68, 0.12)' }
      }
    ];

    if (hasHumidity) {
      yAxis.push({
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} %' },
        splitLine: { show: false }
      });
      chartSeries.push({
        name: 'Luftfeuchte',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: series.humidity.map(point => [point.time, point.value]),
        lineStyle: { width: 2, color: '#3b82f6' },
        itemStyle: { color: '#3b82f6' }
      });
    }

    return {
      grid: { left: 56, right: hasHumidity ? 56 : 16, top: 40, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { top: 0, textStyle: { color: '#94a3b8', fontSize: 11 } },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis,
      series: chartSeries
    };
  }

  /**
   * Zieht den offenen Detaildialog auf die frisch geladenen Messwerte nach.
   * Fehlt der Sensor in der neuen Antwort (Abruffehler liefert eine leere Liste),
   * bleiben die zuletzt gezeigten Werte stehen statt den Dialog wegzureissen —
   * ihr Alter ist im Dialog am Zeitstempel ablesbar.
   */
  private refreshSensorDetail(): void {
    const open = this.sensorDetail;
    if (!open) {
      return;
    }
    const updated = buildSensorDetail(this.currentTemperatures, open.sensorId, Date.now());
    if (updated) {
      this.sensorDetail = updated;
    }
  }

  /** Öffnet den Verbraucher-Dialog und lädt dafür die vollständige Liste. */
  openConsumerDialog(): void {
    if (this.consumerDialogOpen) {
      return;
    }
    this.consumerDialogOpen = true;
    this.loadAllConsumers();
  }

  closeConsumerDialog(): void {
    if (!this.consumerDialogOpen) {
      return;
    }
    this.consumerDialogOpen = false;
    this.allConsumers = [];
  }

  /** Öffnet den Verlaufs-Dialog für einen Verbraucher (immer mit 24-Stunden-Sicht). */
  openHistoryDialog(consumer: PowerConsumer): void {
    this.historyConsumer = consumer;
    this.historyRange = 'DAY';
    this.loadHistory();
  }

  closeHistoryDialog(): void {
    this.historyConsumer = null;
    this.historyRange = 'DAY';
    this.historyOptions = null;
    this.historyEmpty = false;
    this.historyError = null;
  }

  setHistoryRange(range: PowerRange): void {
    if (range === this.historyRange) {
      return;
    }
    this.historyRange = range;
    this.loadHistory();
  }

  private loadHistory(): void {
    const consumer = this.historyConsumer;
    if (!consumer) {
      return;
    }
    const requestedRange = this.historyRange;
    this.historyError = null;
    this.historyEmpty = false;
    this.powerConsumerService.getHistory(consumer.entityId, requestedRange).subscribe({
      next: history => {
        // Verworfen, wenn der Dialog inzwischen geschlossen, gewechselt oder auf einen
        // anderen Zeitraum gestellt wurde: sonst gewinnt eine spaet eintreffende Antwort.
        if (this.historyConsumer?.entityId !== history.entityId
            || this.historyRange !== requestedRange) {
          return;
        }
        this.historyEmpty = history.points.length === 0;
        this.historyOptions = this.buildHistoryOptions(history);
      },
      error: () => {
        // Derselbe Schutz: ein fehlgeschlagener alter Request darf nicht die Fehlermeldung
        // ueber einen inzwischen erfolgreich geladenen neuen Verlauf legen.
        if (this.historyConsumer?.entityId !== consumer.entityId
            || this.historyRange !== requestedRange) {
          return;
        }
        this.historyOptions = null;
        this.historyError = 'Verlauf konnte nicht geladen werden.';
      }
    });
  }

  /** Liniendiagramm des Leistungsverlaufs; null-Werte lassen die Linie bewusst abreißen. */
  private buildHistoryOptions(history: PowerHistory): Record<string, unknown> {
    return {
      grid: { left: 56, right: 16, top: 24, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} W' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      },
      series: [
        {
          name: 'Leistung',
          type: 'line',
          smooth: true,
          showSymbol: false,
          connectNulls: false,
          data: history.points.map(point => [point.time, point.value]),
          lineStyle: { width: 2.5, color: '#f59e0b' },
          itemStyle: { color: '#f59e0b' },
          areaStyle: { color: 'rgba(245, 158, 11, 0.15)' }
        }
      ]
    };
  }

  /** Leistung als "1.250 W"; unavailable-Geräte zeigen einen Strich. */
  powerLabel(consumer: PowerConsumer): string {
    if (consumer.powerWatts == null) {
      return '–';
    }
    return `${Math.round(consumer.powerWatts).toLocaleString('de-DE')} W`;
  }

  /**
   * Verriegeln läuft ohne Rückfrage; Entsperren/Tür öffnen erst nach Bestätigung.
   */
  onNukiAction(lock: NukiLock, action: NukiLockActionType): void {
    if (this.pendingNukiIds.has(lock.smartlockId)) {
      return;
    }
    if (action === 'LOCK') {
      this.executeNukiAction(lock, action);
    } else {
      this.nukiConfirm = { lock, action };
    }
  }

  confirmNukiAction(): void {
    if (!this.nukiConfirm) {
      return;
    }
    const { lock, action } = this.nukiConfirm;
    this.nukiConfirm = null;
    this.executeNukiAction(lock, action);
  }

  cancelNukiAction(): void {
    this.nukiConfirm = null;
  }

  /** Anzeigetext des Schlosszustands. */
  nukiStateLabel(lock: NukiLock): string {
    switch (lock.state) {
      case 'locked':
        return 'Verriegelt';
      case 'unlocked':
        return 'Aufgesperrt';
      case 'unlatched':
        return 'Tür geöffnet';
      case 'locking':
        return 'Verriegelt…';
      case 'unlocking':
        return 'Sperrt auf…';
      case 'unlatching':
        return 'Öffnet…';
      case 'jammed':
        return 'Blockiert!';
      case 'uncalibrated':
        return 'Nicht kalibriert';
      case 'unavailable':
        return 'Nicht erreichbar';
      default:
        return 'Unbekannt';
    }
  }

  /** Material-Symbol zum Schlosszustand. */
  nukiStateIcon(lock: NukiLock): string {
    switch (lock.state) {
      case 'locked':
        return 'lock';
      case 'jammed':
        return 'lock_reset';
      case 'unavailable':
        return 'lock_clock';
      default:
        return 'lock_open';
    }
  }

  /** True, wenn Aktionen für dieses Schloss möglich sind. */
  nukiActionable(lock: NukiLock): boolean {
    return lock.state !== 'unavailable' && !this.pendingNukiIds.has(lock.smartlockId);
  }

  /** Beschriftung der zu bestätigenden Aktion im Dialog. */
  get nukiConfirmLabel(): string {
    if (!this.nukiConfirm) {
      return '';
    }
    return this.nukiConfirm.action === 'UNLATCH' ? 'Tür öffnen' : 'Aufsperren';
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
      .subscribe(readings => {
        this.currentTemperatures = readings;
        this.climate = buildClimateView(readings, Date.now());
        this.refreshSensorDetail();
      });
  }

  /** Haelt die Lueftungs-Karte im Hub aktuell (gleicher Takt wie die Klima-Kacheln). */
  private startVentilationRefresh(): void {
    this.ventilationSubscription = interval(DashboardComponent.CLIMATE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.insightService.getVentilation().pipe(
            catchError(() => of<VentilationAssessment | null>(null))
          )
        )
      )
      .subscribe(assessment => {
        this.ventilationInsight = buildVentilationInsight(assessment);
        this.rebuildInsights();
      });
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

  private startConsumerRefresh(): void {
    this.consumerSubscription = interval(DashboardComponent.CONSUMER_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannte Liste (null = kein Update).
        switchMap(() => this.powerConsumerService
          .getConsumers(DashboardComponent.CONSUMER_TILE_LIMIT)
          .pipe(catchError(() => of<PowerConsumer[] | null>(null))))
      )
      .subscribe(consumers => {
        if (consumers) {
          this.topConsumers = consumers;
        }
        // Der offene Dialog soll dieselbe Aktualität haben wie die Kachel.
        if (this.consumerDialogOpen) {
          this.loadAllConsumers();
        }
      });
  }

  private loadAllConsumers(): void {
    this.powerConsumerService.getConsumers()
      .pipe(catchError(() => of<PowerConsumer[] | null>(null)))
      .subscribe(consumers => {
        if (consumers) {
          this.allConsumers = consumers;
        }
      });
  }

  private startModeRefresh(): void {
    this.modeSubscription = interval(DashboardComponent.MODE_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Modi (null = kein Update).
        switchMap(() => this.modeService.getModes().pipe(catchError(() => of<ModeEntity[] | null>(null))))
      )
      .subscribe(modes => {
        if (modes) {
          this.modes = modes;
          this.modeError = null;
        }
      });
  }

  /**
   * Haelt die Muell-Meldung im Hub aktuell. Neben dem stuendlichen Takt zusaetzlich kurz
   * nach Mitternacht: `daysUntil` wird serverseitig zum Abrufzeitpunkt berechnet, ein rein
   * stuendlicher Takt liesse "Morgen" also bis zu eine Stunde ueber den Tageswechsel
   * hinaus stehen bleiben.
   */
  private startWasteRefresh(): void {
    this.wasteSubscription = merge(
      interval(DashboardComponent.WASTE_REFRESH_MS),
      timer(this.msUntilNextMidnight(), DashboardComponent.DAY_MS)
    )
      .pipe(
        startWith(0),
        switchMap(() => this.wasteService.getUpcoming().pipe(catchError(() => of([]))))
      )
      .subscribe(events => {
        this.wasteInsight = buildWasteInsight(events);
        this.rebuildInsights();
      });
  }

  /** Haelt die Termin-Eintraege im Hub aktuell (gleiches Mitternachts-Muster wie der Muell). */
  private startCalendarRefresh(): void {
    this.calendarSubscription = merge(
      interval(DashboardComponent.CALENDAR_REFRESH_MS),
      timer(this.msUntilNextMidnight(), DashboardComponent.DAY_MS)
    )
      .pipe(
        startWith(0),
        switchMap(() => this.calendarService.getUpcoming(3).pipe(catchError(() => of([]))))
      )
      .subscribe(occurrences => {
        this.calendarInsights = buildCalendarInsights(occurrences);
        this.rebuildInsights();
      });
  }

  /** Komponiert den Hub: Muell voran, dann Termine, Lueften, Tracker-Akku, dahinter die Platzhalter. */
  private rebuildInsights(): void {
    this.insights = [
      ...(this.wasteInsight ? [this.wasteInsight] : []),
      ...this.calendarInsights,
      ...(this.ventilationInsight ? [this.ventilationInsight] : []),
      ...(this.trackerBatteryInsight ? [this.trackerBatteryInsight] : []),
      ...DashboardComponent.PLACEHOLDER_INSIGHTS
    ];
  }

  /**
   * Millisekunden bis kurz nach dem naechsten lokalen Mitternachtswechsel.
   * Der kleine Versatz (5s) ist bewusst: bei exakt 00:00:00 koennte der Server bei
   * minimal abweichender Uhr noch "gestern" liefern.
   */
  private msUntilNextMidnight(): number {
    const now = new Date();
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 5);
    return midnight.getTime() - now.getTime();
  }

  private startNukiRefresh(): void {
    this.nukiSubscription = interval(DashboardComponent.NUKI_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Schlösser (null = kein Update).
        switchMap(() => this.nukiService.getLocks().pipe(catchError(() => of<NukiLock[] | null>(null))))
      )
      .subscribe(locks => {
        this.nukiLoading = false;
        if (locks) {
          this.nukiLocks = locks;
          this.nukiError = null;
        } else if (this.nukiLocks.length === 0) {
          this.nukiError = 'Schloss nicht erreichbar.';
        } else {
          // Alte Schlossdaten bleiben sichtbar, aber als evtl. veraltet gekennzeichnet.
          this.nukiError = 'Verbindung unterbrochen – Anzeige evtl. veraltet.';
        }
      });
  }

  private startPetRefresh(): void {
    this.petSubscription = interval(DashboardComponent.PETS_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Tiere (null = kein Update).
        switchMap(() => this.tractiveService.getPets().pipe(catchError(() => of<TractivePet[] | null>(null))))
      )
      .subscribe(pets => {
        if (pets) {
          this.pets = pets;
          this.trackerBatteryInsight = buildTrackerBatteryInsight(pets);
          this.rebuildInsights();
        }
      });
  }

  /**
   * Haelt den Zustand der Zigbee-Anbindung fuer den Fussleisten-Hinweis aktuell. Das
   * Wandtablet laedt die Seite genau einmal und nie wieder, deshalb dasselbe Polling-Muster
   * wie bei den Nachbar-Kacheln (z. B. {@link startPetRefresh}). Ein fehlgeschlagener Abruf
   * loescht den zuletzt bekannten Wert bewusst nicht (null = kein Update) – sonst wuerde ein
   * einzelner Netzwerkfehler die Kachel verschwinden lassen, obwohl Zigbee gesund ist.
   */
  private startZigbeeHealthRefresh(): void {
    this.zigbeeHealthSubscription = interval(DashboardComponent.ZIGBEE_HEALTH_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.zigbeeService.getHealth().pipe(catchError(() => of<ZigbeeHealth | null>(null))))
      )
      .subscribe(health => {
        if (health) {
          this.zigbeeHealth = health;
        }
      });
  }

  /** Nur Tiere mit einer Aussage; ohne sie bleibt die Kachel leer statt zu raten. */
  get petsWithVerdict(): TractivePet[] {
    return this.pets.filter(pet => pet.atHome != null);
  }

  petStatusLabel(pet: TractivePet): string {
    return pet.atHome ? 'Zu Hause' : 'Unterwegs';
  }

  petStatusIcon(pet: TractivePet): string {
    return pet.atHome ? 'home' : 'pets';
  }

  /** Öffnet den Spaziergänge-Dialog und lädt die letzten 7 Tage pro Hund. */
  openWalksDialog(): void {
    if (this.petsWithVerdict.length === 0) {
      return;
    }
    this.walksDialogOpen = true;
    const requestId = ++this.walksRequestId;
    this.walkSections = this.petsWithVerdict.map(pet => ({
      pet, dayGroups: [], loading: true, error: null, empty: false
    }));
    for (const section of this.walkSections) {
      this.tractiveService.getWalks(section.pet.trackerId).subscribe({
        next: walks => {
          if (requestId !== this.walksRequestId) {
            return;
          }
          section.loading = false;
          section.empty = walks.length === 0;
          section.dayGroups = this.groupWalksByDay(walks);
        },
        error: err => {
          if (requestId !== this.walksRequestId) {
            return;
          }
          section.loading = false;
          section.error = err?.error?.message ?? 'Spaziergänge konnten nicht geladen werden.';
        }
      });
    }
  }

  closeWalksDialog(): void {
    this.walksDialogOpen = false;
    this.walksRequestId++;
    this.walkSections = [];
  }

  /** Gruppiert nach Kalendertag; die Reihenfolge (neueste zuerst) kommt vom Server. */
  private groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
    const groups: { label: string; walks: TractiveWalk[] }[] = [];
    for (const walk of walks) {
      const label = new Date(walk.start).toLocaleDateString('de-DE', {
        weekday: 'long', day: 'numeric', month: 'long'
      });
      const last = groups[groups.length - 1];
      if (last && last.label === label) {
        last.walks.push(walk);
      } else {
        groups.push({ label, walks: [walk] });
      }
    }
    return groups;
  }

  walkTimeRange(walk: TractiveWalk): string {
    const format = (iso: string) =>
      new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    return `${format(walk.start)}–${format(walk.end)} Uhr`;
  }

  walkDuration(walk: TractiveWalk): string {
    const hours = Math.floor(walk.durationMinutes / 60);
    const minutes = walk.durationMinutes % 60;
    return hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`;
  }

  walkDistance(walk: TractiveWalk): string {
    return walk.distanceMeters >= 1000
      ? `${(walk.distanceMeters / 1000).toFixed(1).replace('.', ',')} km`
      : `${Math.round(walk.distanceMeters)} m`;
  }

  private executeNukiAction(lock: NukiLock, action: NukiLockActionType): void {
    this.pendingNukiIds.add(lock.smartlockId);
    this.nukiError = null;
    this.nukiService.sendAction(lock.smartlockId, action).subscribe({
      next: () => {
        this.pendingNukiIds.delete(lock.smartlockId);
        this.refreshNukiLocks();
      },
      error: () => {
        this.pendingNukiIds.delete(lock.smartlockId);
        this.nukiError = `${lock.name}: Aktion fehlgeschlagen.`;
      }
    });
  }

  private refreshNukiLocks(): void {
    this.nukiService.getLocks().pipe(catchError(() => of<NukiLock[]>([]))).subscribe(locks => {
      if (locks.length > 0) {
        this.nukiLocks = locks;
      }
    });
  }

  private loadTopSwitches(): void {
    this.topSwitchRequest().subscribe(switches => (this.topSwitches = switches));
  }

  private topSwitchRequest() {
    return this.switchService.getSwitches(DashboardComponent.SWITCH_TILE_LIMIT, 'tile').pipe(
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

/** Szenen-Schnellwahl-Button. */
interface SceneButton {
  readonly label: string;
  readonly active: boolean;
}

/** Hinweis-Karte im Intelligence Hub. */
interface IntelligenceItem {
  readonly icon: string;
  readonly tone: 'primary' | 'secondary' | 'muted' | 'tertiary' | 'error';
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

/** Spaziergänge eines Hundes, gruppiert nach Tag. */
interface PetWalkSection {
  pet: TractivePet;
  dayGroups: { label: string; walks: TractiveWalk[] }[];
  loading: boolean;
  error: string | null;
  empty: boolean;
}

