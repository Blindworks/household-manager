import { Component, HostListener, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription, interval, merge, of, startWith, switchMap, timer } from 'rxjs';
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
import { weatherSymbol, weatherMaterialSymbol } from '../../shared/weather-icon.util';
import { TABLET_VIEWS } from '../../shared/tablet-views';
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
import { buildDoorInsights } from '../../shared/door-insight.util';
import { buildApplianceInsights } from '../../shared/appliance-insight.util';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState } from '../../models/entity-state.model';
import { VentilationAssessment } from '../../models/ventilation.model';
import { HubInsight } from '../../shared/hub-insight.model';
import { SwitchService } from '../../services/switch.service';
import { SwitchEntity } from '../../models/switch.model';
import { ModeService } from '../../services/mode.service';
import { SystemService } from '../../services/system.service';
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
import { PetSupplyService } from '../../services/pet-supply.service';
import { PetSupply } from '../../models/pet-supply.model';
import { PresenceService } from '../../services/presence.service';
import { PresencePersonStatus, PresenceStatusResponse } from '../../models/presence.model';
import { formatDate } from '@angular/common';
import { iconOffVariant } from '../../shared/icon-off.util';
import { PetSupplyTone, petSupplyIcon as petSupplyLevelIcon, petSupplyTone as petSupplyLevelTone, worstPetSupplyTone } from '../../shared/pet-supply-level.util';
import {
  groupWalksByDay as groupWalks,
  walkDistance as formatWalkDistance,
  walkDuration as formatWalkDuration,
  walkTimeRange as formatWalkTimeRange
} from '../../shared/walk-format.util';
import {
  ActivationCheck,
  buildConsumerCheck,
  buildContactCheck,
  failedCheck,
  loadingCheck
} from '../../shared/mode-activation-check.util';

// LegendComponent: ohne Legende ist bei zwei Linien nicht erkennbar, welche welche ist.
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

/**
 * Dashboard component - "Lumina" Wand-Dashboard.
 * Vollflaechige Kommandozentrale im Kiosk-Stil: grosse Uhr, Wetter, Kacheln fuer
 * Schalter und Verbraucher, Szenen, Intelligence Hub, Live-Energie-Ring
 * und Modus-Schnellaktionen.
 *
 * Echte Daten: Uhr, Wetter (WeatherService), Live-Energie (EnergyLiveService),
 * Aussentemperaturen, Schalter, Verbraucher, Modi, Müllabfuhr, Türkontakte und
 * Türschloss.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule, EnergyFlowComponent, SwitchListComponent, NgxEchartsDirective],
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
  private readonly systemService = inject(SystemService);
  private readonly wasteService = inject(WasteCollectionService);
  private readonly calendarService = inject(CalendarService);
  private readonly nukiService = inject(NukiService);
  private readonly powerConsumerService = inject(PowerConsumerService);
  private readonly tractiveService = inject(TractiveService);
  private readonly zigbeeService = inject(ZigbeeService);
  private readonly entityStateService = inject(EntityStateService);
  private readonly petSupplyService = inject(PetSupplyService);
  private readonly presenceService = inject(PresenceService);

  /** Umschalter zwischen Website- und Tablet-Ansicht (blendet den Header aus). */
  readonly viewMode = inject(ViewModeService);

  /**
   * Einstiege in die Tablet-Unteransichten. In der Tablet-Ansicht fehlt der
   * Header komplett, deshalb ist diese Leiste dort der einzige Weg weg vom
   * Dashboard – und jede verlinkte Seite braucht einen eigenen Zurueck-Knopf.
   * Ein weiterer Eintrag kostet genau eine Zeile.
   */
  readonly tabletViews = TABLET_VIEWS;

  private readonly tileAccordion = inject(DashboardAccordionService);

  /**
   * True, wenn die Kacheln Schalter/Verbraucher in der kompakten
   * Tablet-Darstellung stehen. Nur in der Tablet-Ansicht – in der
   * Website-Ansicht bleibt das mehrspaltige Raster.
   */
  get accordionActive(): boolean {
    return this.viewMode.isTabletView();
  }

  /**
   * Kacheln, die in der Tablet-Ansicht dauerhaft offen stehen.
   * Die Schalter sollen auf dem Wandtablet immer sichtbar sein – nur was
   * darueber hinausgeht, wird aufklappbar.
   */
  private static readonly ALWAYS_OPEN_TILES: readonly DashboardTileKey[] = ['switches'];

  /** True, wenn die Kachel in der Tablet-Ansicht einen Aufklapp-Kopf hat. */
  isTileCollapsible(key: DashboardTileKey): boolean {
    return this.accordionActive && !DashboardComponent.ALWAYS_OPEN_TILES.includes(key);
  }

  /** True, wenn die Kachel in der Tablet-Ansicht ohne Aufklapp-Kopf dauerhaft offen steht. */
  isTileStatic(key: DashboardTileKey): boolean {
    return this.accordionActive && !this.isTileCollapsible(key);
  }

  /** True, wenn der Inhalt der Kachel sichtbar ist (nur aufklappbare Kacheln koennen zu sein). */
  isTileOpen(key: DashboardTileKey): boolean {
    return !this.isTileCollapsible(key) || this.tileAccordion.isOpen(key);
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
  private doorSubscription?: Subscription;
  private applianceSubscription?: Subscription;
  private petSupplySubscription?: Subscription;
  private presenceSubscription?: Subscription;

  /** Umfang des SVG-Rings (r = 40 -> 2*pi*40). */
  private static readonly RING_CIRCUMFERENCE = 251.2;
  /** Hausverbrauch, der den Verbrauchs-Ring komplett fuellt (5 kW). */
  private static readonly CONSUMPTION_MAX_WATT = 5000;
  /** PV-Erzeugung, die den PV-Ring komplett fuellt (2 kW). */
  private static readonly PV_MAX_WATT = 2000;
  /** Netzbezug, der den Bezugs-Ring komplett fuellt (5 kW). */
  private static readonly GRID_MAX_WATT = 5000;
  /** Aktualisierungsintervall der Temperaturmesswerte (60 s). */
  private static readonly CLIMATE_REFRESH_MS = 60000;
  /** Anzahl der Schalter auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly SWITCH_TILE_LIMIT = 8;
  /** Aktualisierungsintervall der Schalter-Kachel (30 s). */
  private static readonly SWITCH_REFRESH_MS = 30000;
  /** Aktualisierungsintervall der Modus-Leiste (30 s; Flows schalten Modi auch von aussen). */
  private static readonly MODE_REFRESH_MS = 30000;
  /** Farbton je Modus-Position (bestehende lumina__mode-Varianten); error traegt der Reboot-Button. */
  private static readonly MODE_TONES = ['primary', 'tertiary', 'neutral', 'neutral'] as const;
  /** Wartezeit nach dem Reboot, bevor das Reload-Polling beginnt. */
  private static readonly REBOOT_POLL_DELAY_MS = 15000;
  /** Abstand der Erreichbarkeits-Checks waehrend des Neustarts. */
  private static readonly REBOOT_POLL_INTERVAL_MS = 5000;
  /** Haelt die Muell-Meldung ueber den Tag hinweg aktuell (z. B. bei geaenderten Einstellungen). */
  private static readonly WASTE_REFRESH_MS = 3600000;
  /** Kalender-Hub-Eintraege alle 5 Minuten auffrischen (Termine aendern sich haeufiger als Muell). */
  private static readonly CALENDAR_REFRESH_MS = 300000;
  private static readonly DAY_MS = 86400000;
  /** Aktualisierungsintervall der Türschloss-Kachel (30 s). */
  private static readonly NUKI_REFRESH_MS = 30000;
  /** Aktualisierungsintervall der Tuer-offen-Hinweise im Hub (30 s). */
  private static readonly DOOR_REFRESH_MS = 30000;
  /** Wie die Tuerkarten: die Helfer aendern sich selten, 30 s reichen. */
  private static readonly APPLIANCE_REFRESH_MS = 30000;
  /** Anzahl der Verbraucher auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly CONSUMER_TILE_LIMIT = 4;
  /** Aktualisierungsintervall der Verbraucher-Kachel (30 s). */
  private static readonly CONSUMER_REFRESH_MS = 30000;
  private static readonly PETS_REFRESH_MS = 60000;
  /** Aktualisierungsintervall der Zigbee-Health-Kachel (60 s; das Wandtablet laedt die Seite nur einmal). */
  private static readonly ZIGBEE_HEALTH_REFRESH_MS = 60000;
  /** Aktualisierungsintervall der Vorrats-Kacheln (10 min; die Bestaende aendern sich zweimal am Tag). */
  private static readonly PET_SUPPLY_REFRESH_MS = 600000;
  /** Anwesenheits-Kachel: 30-s-Rhythmus wie der Backend-Poller. */
  private static readonly PRESENCE_REFRESH_MS = 30000;

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


  /**
   * Hinweise des Intelligence Hub; Komposition und Reihenfolge baut
   * {@link rebuildInsights}. Leer = keine Hinweise, der Hub zeigt eine Ruhemeldung.
   */
  insights: HubInsight[] = [];

  /** Zuletzt gebaute Muell-Meldung; null = nichts ansteht. */
  private wasteInsight: HubInsight | null = null;
  /** Zuletzt gebaute Kalender-Eintraege (max. 3). */
  private calendarInsights: HubInsight[] = [];
  /** Zuletzt gebaute Lueftungs-Karte; null = keine Empfehlung. */
  private ventilationInsight: HubInsight | null = null;
  /** Zuletzt gebaute Tracker-Akku-Warnung; null = alle Akkus ausreichend. */
  private trackerBatteryInsight: HubInsight | null = null;
  /** Zuletzt gebaute Tuer-offen-Karten (Haustuer/Terrassentuer); leer = alles zu. */
  private doorInsights: HubInsight[] = [];
  /** Karten fuer fertige Maschinen; gesetzt von den Flows ueber Helfer-Entitaeten. */
  private applianceInsights: HubInsight[] = [];

  /**
   * Letzter geladener Stand der manuellen Helfer. Wird beim Wegtippen gebraucht,
   * um das Ziel neu aufzuloesen, statt einer festgehaltenen Kopie zu vertrauen.
   */
  private applianceEntities: EntityState[] = [];

  /** Haus-Modi der Fussleiste, vom Backend geladen. */
  modes: ModeEntity[] = [];
  /** Entity-IDs mit laufendem Modus-Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingModeIds = new Set<string>();
  modeError: string | null = null;

  /**
   * Modi mit Aktivierungs-Checks (Fenster/Türen, Großverbraucher): beim
   * Einschalten öffnet ein Dialog statt direkt zu schalten. Reiner UI-Schutz —
   * Telegram, Flows und API schalten unverändert direkt (Muster confirmRequired).
   */
  private static readonly CHECKED_MODE_IDS = new Set([
    'input_boolean.manual_toni_allein',
    'input_boolean.manual_abwesend'
  ]);

  /** Modus, für den der Aktivierungs-Check-Dialog offen ist; null = geschlossen. */
  modeCheckMode: ModeEntity | null = null;
  modeCheckContacts: ActivationCheck = loadingCheck();
  modeCheckConsumers: ActivationCheck = loadingCheck();

  /** True, solange der Reboot-Bestätigungsdialog offen ist. */
  rebootConfirm = false;
  /** True ab bestätigtem Reboot bis zum automatischen Neuladen der Seite. */
  rebootInProgress = false;
  /** Erreichbarkeits-Polling nach dem Reboot (lädt die Seite neu). */
  private rebootPollSubscription?: Subscription;

  /** Nuki-Schlösser für die Türschloss-Kachel. */
  nukiLocks: NukiLock[] = [];
  /** True, solange noch keine Nuki-Antwort vorliegt (unterscheidet "lädt" von "keine Schlösser"). */
  nukiLoading = true;
  nukiError: string | null = null;
  /** Smartlock-IDs mit laufender Aktion (verhindert Doppelklicks). */
  readonly pendingNukiIds = new Set<number>();
  /** Zu bestätigende Aktion (Entsperren/Tür öffnen); null = kein Dialog offen. */
  nukiConfirm: { lock: NukiLock; action: NukiLockActionType } | null = null;
  /**
   * True, solange die Türschloss-Kachel ausgefahren ist. Sie steht standardmäßig
   * zusammengeschrumpft (nur Symbol), fährt per Klick aus und klappt danach von
   * selbst wieder zu. Kein Timer beim Start: ein offener setTimeout aus ngOnInit
   * würde jeden fakeAsync-Test des Dashboards mit "timer still in the queue" brechen.
   */
  nukiExpanded = false;
  /** Wartezeit, bis eine ausgefahrene Fusszeilen-Kachel von selbst wieder zuklappt. */
  private static readonly CARD_COLLAPSE_DELAY_MS = 6000;
  private nukiCollapseTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * True, solange die Toni-Kachel ausgefahren ist. Sie fasst Hund und Vorraete
   * zusammen und verhaelt sich wie die Tuerschloss-Kachel: zusammengeschrumpft
   * nur das Symbol, per Klick aus, danach von selbst wieder zu. Kein Timer beim
   * Start - siehe nukiExpanded.
   */
  toniExpanded = false;
  private toniCollapseTimer: ReturnType<typeof setTimeout> | null = null;

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

  /** Toni-Futtervorrat; null = Kachel wird nicht gerendert. */
  petSupplies: PetSupply[] = [];

  /** Erfassungs-Dialog der Vorrats-Kacheln (Einkauf zubuchen, Bestand korrigieren). */
  /**
   * Der Dialog haelt nur den SCHLUESSEL, nicht das Vorratsobjekt: der
   * 10-Minuten-Refresh laeuft weiter, waehrend der Dialog offen ist, und eine
   * festgehaltene Kopie waere danach veraltet (Regel aus confirmToggle).
   */
  petSupplyDialogKey: string | null = null;
  petSupplyPurchaseAmount: number | null = null;
  petSupplyPurchaseNote = '';
  petSupplyCorrectionAmount: number | null = null;
  petSupplyCorrectionNote = '';
  petSupplySaving = false;
  petSupplyError: string | null = null;

  /** Anwesenheits-Status; null = Kachel wird nicht gerendert. */
  presence: PresenceStatusResponse | null = null;

  ngOnInit(): void {
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
    this.startDoorRefresh();
    this.startApplianceRefresh();
    this.startPetSupplyRefresh();
    this.startPresenceRefresh();
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
    this.doorSubscription?.unsubscribe();
    this.applianceSubscription?.unsubscribe();
    this.petSupplySubscription?.unsubscribe();
    this.presenceSubscription?.unsubscribe();
    this.rebootPollSubscription?.unsubscribe();
    this.clearNukiCollapseTimer();
    this.clearToniCollapseTimer();
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
    this.closeModeCheckDialog();
    this.nukiConfirm = null;
    this.rebootConfirm = false;
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
   * Schaltet einen Schalter. Geschuetzte Schalter oeffnen beim AUSschalten den
   * Bestaetigungsdialog; erst der Klick auf den Schalter im Dialog fuehrt den Toggle
   * aus. Einschalten laeuft immer direkt - ein versehentliches Einschalten ist
   * harmlos, ein versehentliches Ausschalten (Kuehlschrank, Router) nicht.
   *
   * Die Richtung wird aus dem zuletzt geladenen Client-Zustand abgeleitet und
   * spiegelt damit die Regel des Backends (alles ausser "on" schaltet ein,
   * SwitchCommandService.toggleDevice) - beide Seiten muessen zusammen geaendert
   * werden. Da die Kacheln nur alle 30 s nachladen, kann ein anderswo
   * eingeschalteter Schalter in diesem Fenster ohne Rueckfrage ausgeschaltet
   * werden; bewusst akzeptiert, der Schutz ist ausdruecklich UI-seitig.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    if (entity.confirmRequired && entity.state === 'on') {
      this.confirmSwitch = entity;
      this.confirmSwitchList = [entity];
      return;
    }
    this.executeToggle(entity);
  }

  /**
   * Bestätigung im Dialog: schließt ihn und führt den eigentlichen Toggle aus. Löst den
   * Schalter über seine entityId in `allSwitches`/`topSwitches` neu auf statt die im Dialog
   * gehaltene Referenz weiterzuverwenden - ein während offenem Dialog eintreffender
   * Hintergrund-Refresh könnte ihn bereits ausgeschaltet haben (Muster wie
   * `SmartDeviceListComponent.confirmTurnOff`); ohne Neu-Aufloesung würde `executeToggle`
   * ihn dann ausgerechnet über den "Ausschalten"-Bestätigungsdialog wieder einschalten.
   *
   * Beide Listen können beim Aufloesen leer sein - `loadTopSwitches` leert `topSwitches`
   * bei einem fehlgeschlagenen 30s-Refresh (WLAN-Aussetzer auf dem Wandtablet ist
   * Alltag, kein Sonderfall), und `SWITCH_TILE_LIMIT` kann einen weiterhin an
   * geschuetzten Schalter aus den Top 8 verdraengen, waehrend der Dialog offen ist.
   * "Nicht gefunden" ist dabei KEIN Beleg dafuer, dass der Schalter aus ist - toggleSwitch
   * oeffnet den Dialog ueberhaupt nur, wenn state === 'on' war, die gehaltene Referenz ist
   * also per Konstruktion "an". Deshalb faellt die Aufloesung zuletzt auf sie zurueck,
   * statt bei einer leeren Liste stillschweigend gar nicht zu schalten.
   */
  confirmToggle(entity: SwitchEntity): void {
    this.closeConfirmDialog();
    const current = this.allSwitches.find(s => s.entityId === entity.entityId)
      ?? this.topSwitches.find(s => s.entityId === entity.entityId)
      ?? entity;
    if (current.state === 'on') {
      this.executeToggle(current);
    }
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

  /** Icon eines Modus: im Aus-Zustand die durchgestrichene Variante, falls es eine gibt. */
  modeIcon(mode: ModeEntity): string {
    return mode.state === 'on' ? mode.icon : iconOffVariant(mode.icon);
  }

  /**
   * Schaltet einen Haus-Modus. Beim Einschalten eines bewachten Modus
   * ("Toni allein", "Abwesend") öffnet stattdessen der Check-Dialog —
   * Ausschalten bleibt immer direkt.
   */
  toggleMode(mode: ModeEntity): void {
    if (mode.state !== 'on' && DashboardComponent.CHECKED_MODE_IDS.has(mode.entityId)) {
      this.openModeCheckDialog(mode);
      return;
    }
    this.performModeToggle(mode);
  }

  /**
   * Führt den Toggle aus. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt (gleiches Muster wie {@link toggleSwitch}).
   */
  private performModeToggle(mode: ModeEntity): void {
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

  /** Öffnet den Check-Dialog und startet beide Prüfungen parallel. */
  private openModeCheckDialog(mode: ModeEntity): void {
    this.modeCheckMode = mode;
    this.modeCheckContacts = loadingCheck();
    this.modeCheckConsumers = loadingCheck();

    this.entityStateService.getEntities('BINARY_SENSOR', 'ZIGBEE').subscribe({
      next: entities => this.modeCheckContacts = buildContactCheck(entities),
      error: () => this.modeCheckContacts = failedCheck()
    });
    this.powerConsumerService.getConsumers().subscribe({
      next: consumers => this.modeCheckConsumers = buildConsumerCheck(consumers),
      error: () => this.modeCheckConsumers = failedCheck()
    });
  }

  /**
   * Aktiviert den Modus aus dem Check-Dialog. Der Modus wird aus der aktuellen
   * Liste re-resolved (Muster confirmToggle): ist er inzwischen an — z. B. per
   * Telegram oder Flow, die Liste wird alle 30 s aufgefrischt — würde der Toggle
   * ihn ausgerechnet wieder ausschalten, also passiert dann nichts.
   */
  confirmModeActivation(): void {
    const dialogMode = this.modeCheckMode;
    this.closeModeCheckDialog();
    if (!dialogMode) {
      return;
    }
    const current = this.modes.find(item => item.entityId === dialogMode.entityId);
    if (!current || current.state === 'on') {
      return;
    }
    this.performModeToggle(current);
  }

  closeModeCheckDialog(): void {
    this.modeCheckMode = null;
  }

  /** Material-Symbol für den Anzeige-Zustand eines Checks. */
  modeCheckIcon(check: ActivationCheck): string {
    if (check.status === 'ok') {
      return 'check_circle';
    }
    return check.status === 'warning' ? 'warning' : 'hourglass_empty';
  }

  private applyModeState(entityId: string, state: string): void {
    const match = this.modes.find(item => item.entityId === entityId);
    if (match) {
      match.state = state;
    }
  }

  /** Öffnet den Reboot-Bestätigungsdialog (der Neustart selbst folgt erst nach Bestätigung). */
  openRebootDialog(): void {
    if (this.rebootInProgress) {
      return;
    }
    this.rebootConfirm = true;
  }

  cancelReboot(): void {
    this.rebootConfirm = false;
  }

  /**
   * Löst den System-Neustart aus. Danach pollt das Dashboard das Backend und
   * lädt die Seite automatisch neu, sobald es wieder antwortet.
   */
  confirmReboot(): void {
    this.rebootConfirm = false;
    this.rebootInProgress = true;
    this.modeError = null;
    this.systemService.reboot().subscribe({
      next: () => this.startRebootReloadPolling(),
      error: (err: Error) => {
        this.rebootInProgress = false;
        this.modeError = err.message;
      }
    });
  }

  private startRebootReloadPolling(): void {
    this.rebootPollSubscription = timer(
      DashboardComponent.REBOOT_POLL_DELAY_MS,
      DashboardComponent.REBOOT_POLL_INTERVAL_MS
    )
      .pipe(switchMap(() => this.systemService.ping().pipe(catchError(() => of(null)))))
      .subscribe(result => {
        if (result !== null) {
          this.rebootPollSubscription?.unsubscribe();
          this.reloadPage();
        }
      });
  }

  /** Als eigene Methode gekapselt, damit Tests den harten Reload stubben können. */
  reloadPage(): void {
    window.location.reload();
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
   * Klappt die Türschloss-Kachel auf oder zu. Ausgefahren läuft ein Timer, der sie
   * wieder zusammenklappt; zugeklappt gibt es nichts zu warten.
   */
  toggleNukiCard(): void {
    this.nukiExpanded = !this.nukiExpanded;
    if (this.nukiExpanded) {
      this.scheduleNukiCollapse();
    } else {
      this.clearNukiCollapseTimer();
    }
  }

  /** Startet die Wartezeit bis zum automatischen Zusammenklappen neu. */
  private scheduleNukiCollapse(): void {
    this.clearNukiCollapseTimer();
    this.nukiCollapseTimer = setTimeout(() => {
      this.nukiCollapseTimer = null;
      this.nukiExpanded = false;
    }, DashboardComponent.CARD_COLLAPSE_DELAY_MS);
  }

  private clearNukiCollapseTimer(): void {
    if (this.nukiCollapseTimer !== null) {
      clearTimeout(this.nukiCollapseTimer);
      this.nukiCollapseTimer = null;
    }
  }

  /** True, sobald es etwas zu zeigen gibt; sonst entfaellt die Toni-Kachel ganz. */
  get hasToniCard(): boolean {
    return this.petsWithVerdict.length > 0 || this.petSupplies.length > 0;
  }

  /**
   * Schlechtester Vorratston - er faerbt das Pfoten-Symbol, damit die
   * EINGEKLAPPTE Kachel den Stand ueberhaupt verraet. Ohne Vorraete bleibt es
   * beim neutralen "ok": eine leere Liste ist kein Notstand.
   */
  get toniTone(): PetSupplyTone {
    return worstPetSupplyTone(this.petSupplies);
  }

  /** Klappt die Toni-Kachel auf oder zu (Gegenstueck zu toggleNukiCard). */
  toggleToniCard(): void {
    this.toniExpanded = !this.toniExpanded;
    if (this.toniExpanded) {
      this.scheduleToniCollapse();
    } else {
      this.clearToniCollapseTimer();
    }
  }

  private scheduleToniCollapse(): void {
    this.clearToniCollapseTimer();
    this.toniCollapseTimer = setTimeout(() => {
      this.toniCollapseTimer = null;
      this.toniExpanded = false;
    }, DashboardComponent.CARD_COLLAPSE_DELAY_MS);
  }

  private clearToniCollapseTimer(): void {
    if (this.toniCollapseTimer !== null) {
      clearTimeout(this.toniCollapseTimer);
      this.toniCollapseTimer = null;
    }
  }

  /**
   * Verriegeln läuft ohne Rückfrage; Entsperren/Tür öffnen erst nach Bestätigung.
   */
  onNukiAction(lock: NukiLock, action: NukiLockActionType): void {
    // Wer gerade schaltet, soll das Ergebnis noch sehen: Wartezeit neu starten.
    if (this.nukiExpanded) {
      this.scheduleNukiCollapse();
    }
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
    return weatherMaterialSymbol(this.weather?.current?.icon);
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

  /** Haelt die Lueftungs-Karte im Hub aktuell (gleicher Takt wie die Messwerte). */
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

  /** Klick/Tastatur auf eine Hub-Karte; nur antippbare Karten tun etwas. */
  activateInsight(item: HubInsight): void {
    if (item.dismissEntityId) {
      this.dismissInsight(item.dismissEntityId);
    }
  }

  /**
   * Raeumt eine antippbare Hub-Karte weg, indem der zugehoerige Helfer ausgeschaltet
   * wird.
   *
   * <p>Der Endpunkt ist ein *Toggle* und die Helfer-Liste bis zu 30 s alt: ohne die
   * Neuaufloesung aus dem aktuellen Stand wuerde ein Klick auf eine inzwischen
   * anderswo abgeraeumte Karte den Helfer wieder EINschalten (Regel aus
   * {@link confirmToggle}). Schlaegt das Schalten fehl, bleibt die Karte stehen —
   * sie ist die ehrliche Anzeige des Serverzustands.
   */
  dismissInsight(entityId: string): void {
    const current = this.applianceEntities.find(entity => entity.entityId === entityId);
    if (current?.state !== 'on') {
      this.refreshApplianceInsights();
      return;
    }
    this.switchService.toggle(entityId).subscribe({
      next: () => {
        this.applianceEntities = this.applianceEntities.map(entity =>
          entity.entityId === entityId ? { ...entity, state: 'off' } : entity);
        this.refreshApplianceInsights();
      },
      error: () => { /* Karte bleibt stehen, bis der naechste Refresh die Wahrheit bringt. */ }
    });
  }

  private refreshApplianceInsights(): void {
    this.applianceInsights = buildApplianceInsights(this.applianceEntities, Date.now());
    this.rebuildInsights();
  }

  /** Komponiert den Hub: offene Tueren voran, dann fertige Maschinen, Muell, Termine, Lueften, Tracker-Akku. */
  private rebuildInsights(): void {
    this.insights = [
      ...this.doorInsights,
      ...this.applianceInsights,
      ...(this.wasteInsight ? [this.wasteInsight] : []),
      ...this.calendarInsights,
      ...(this.ventilationInsight ? [this.ventilationInsight] : []),
      ...(this.trackerBatteryInsight ? [this.trackerBatteryInsight] : [])
    ];
  }

  /**
   * Haelt die Tuer-offen-Karten im Hub aktuell. Ein Ladefehler leert die Karten
   * bewusst (Muster Lueftung): eine veraltete "Tuer offen"-Meldung waere schlimmer
   * als eine kurz fehlende — bei einem Zigbee-Ausfall werden die Kontakte ohnehin
   * `unavailable` und zaehlen dann nicht als offen.
   */
  private startDoorRefresh(): void {
    this.doorSubscription = interval(DashboardComponent.DOOR_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.entityStateService.getEntities('BINARY_SENSOR', 'ZIGBEE')
          .pipe(catchError(() => of([]))))
      )
      .subscribe(entities => {
        this.doorInsights = buildDoorInsights(entities, Date.now());
        this.rebuildInsights();
      });
  }

  /**
   * Haelt die Karten fertiger Maschinen aktuell. Quelle sind die Helfer, die die
   * Flows "Waschmaschine/Spuelmaschine fertig" setzen. Ein Ladefehler leert die
   * Karten bewusst (Muster Tueren): eine Karte ohne bekannten Serverzustand liesse
   * sich auch nicht mehr sinnvoll wegtippen.
   */
  private startApplianceRefresh(): void {
    this.applianceSubscription = interval(DashboardComponent.APPLIANCE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.entityStateService.getEntities('INPUT_BOOLEAN', 'MANUAL')
          .pipe(catchError(() => of([]))))
      )
      .subscribe(entities => {
        this.applianceEntities = entities;
        this.refreshApplianceInsights();
      });
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

  /**
   * Haelt die Vorrats-Kacheln aktuell (Muster {@link startPetRefresh}; startWith(0)
   * uebernimmt den initialen Load). Das Wandtablet laedt die Seite genau einmal — ohne
   * Polling bliebe die Kachel wochenlang auf dem Stand des Seitenladens. 10 Minuten
   * reichen, der Bestand aendert sich nur zweimal am Tag. Ein fehlgeschlagener Abruf
   * behaelt den letzten Stand (null = kein Update) statt die Kacheln verschwinden zu lassen.
   */
  private startPetSupplyRefresh(): void {
    this.petSupplySubscription = interval(DashboardComponent.PET_SUPPLY_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.petSupplyService.getSupplies().pipe(catchError(() => of<PetSupply[] | null>(null))))
      )
      .subscribe(supplies => {
        if (supplies) {
          this.petSupplies = supplies;
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

  /** Symbol der Vorrats-Kachel; siehe petSupplyIcon fuer die Zuordnung. */
  petSupplyIcon(supply: PetSupply): string {
    return petSupplyLevelIcon(supply);
  }

  petSupplyTone(supply: PetSupply): PetSupplyTone {
    return petSupplyLevelTone(supply);
  }

  /**
   * Der Vorrat des offenen Dialogs, JEDES MAL frisch aus der aktuellen Liste
   * aufgeloest — ein Hintergrund-Refresh bei offenem Dialog darf nicht dazu
   * fuehren, dass auf einem veralteten Stand gebucht wird.
   */
  get petSupplyDialogSupply(): PetSupply | null {
    if (this.petSupplyDialogKey === null) {
      return null;
    }
    return this.petSupplies.find(supply => supply.key === this.petSupplyDialogKey) ?? null;
  }

  /** Öffnet den Erfassungs-Dialog; die Korrektur ist mit dem aktuellen Bestand vorbelegt. */
  openPetSupplyDialog(supply: PetSupply): void {
    this.petSupplyDialogKey = supply.key;
    this.petSupplyError = null;
    this.petSupplyPurchaseAmount = null;
    this.petSupplyPurchaseNote = '';
    this.petSupplyCorrectionAmount = supply.amountRemaining;
    this.petSupplyCorrectionNote = '';
  }

  closePetSupplyDialog(): void {
    this.petSupplyDialogKey = null;
  }

  submitPetSupplyPurchase(): void {
    const supply = this.petSupplyDialogSupply;
    if (supply === null || this.petSupplyPurchaseAmount == null || this.petSupplyPurchaseAmount <= 0) {
      return;
    }
    this.mutatePetSupply(
      this.petSupplyService.recordPurchase(supply.key, this.petSupplyPurchaseAmount, this.petSupplyPurchaseNote),
      () => { this.petSupplyPurchaseAmount = null; this.petSupplyPurchaseNote = ''; });
  }

  submitPetSupplyCorrection(): void {
    const supply = this.petSupplyDialogSupply;
    if (supply === null || this.petSupplyCorrectionAmount == null || this.petSupplyCorrectionAmount < 0) {
      return;
    }
    this.mutatePetSupply(
      this.petSupplyService.correctStock(supply.key, this.petSupplyCorrectionAmount, this.petSupplyCorrectionNote),
      () => { this.petSupplyCorrectionNote = ''; });
  }

  /** Der Dialog bleibt nach dem Buchen offen, damit der neue Füllstand sofort sichtbar ist. */
  private mutatePetSupply(request: Observable<PetSupply>, onSuccess: () => void): void {
    this.petSupplySaving = true;
    this.petSupplyError = null;
    request.subscribe({
      next: updated => {
        this.petSupplySaving = false;
        this.petSupplies = this.petSupplies.map(supply => supply.key === updated.key ? updated : supply);
        this.petSupplyCorrectionAmount = updated.amountRemaining;
        onSuccess();
      },
      error: (err: Error) => {
        this.petSupplySaving = false;
        this.petSupplyError = err.message;
      }
    });
  }

  /**
   * Anwesenheitsanzeige in der Kopfzeile. Ein fehlgeschlagener Refresh
   * behaelt den letzten Stand (null = kein Update) statt die Anzeige
   * verschwinden zu lassen.
   */
  private startPresenceRefresh(): void {
    this.presenceSubscription = interval(DashboardComponent.PRESENCE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.presenceService.getStatus()
          .pipe(catchError(() => of<PresenceStatusResponse | null>(null))))
      )
      .subscribe(status => {
        if (status) {
          this.presence = status;
        }
      });
  }

  /** Nur Personen mit erfassten Geraeten; ohne sie bleibt die Anzeige weg. */
  get presencePersons(): PresencePersonStatus[] {
    return this.presence?.persons ?? [];
  }

  /** `trackBy` fuer die Personenkreise: Objekte kommen alle 30 s frisch vom
   *  Refresh, ohne Identitaet ueber `userId` risse Angular auf dem dauerhaft
   *  laufenden Wandtablet bei jedem Poll alle Kreise ab und baute sie neu. */
  trackByPersonId(_index: number, person: PresencePersonStatus): number {
    return person.userId;
  }

  /**
   * `unknown` bekommt einen eigenen (neutralen) Ring und darf NICHT wie
   * „abwesend" aussehen: nach jedem Backend-Neustart steht jede Person bis
   * zum Ablauf der Karenzzeit auf `unknown` (die Messwerte leben nur im
   * Speicher). Faltete man das in „abwesend", zeigte das Wandtablet nach
   * jedem Deploy minutenlang einen leeren Haushalt als komplett rot an.
   * `unavailable` (keine aktiven Geraete) ist ebenfalls keine Aussage ueber
   * Anwesenheit und bekommt deshalb denselben neutralen Ring wie `unknown`.
   *
   * Beide Abbildungen (diese und `presenceLabel`) zaehlen jeden Zustand
   * einzeln auf und haben bewusst KEIN `default` — mit `noImplicitReturns`
   * wird eine kuenftige fuenfte Auspraegung von `PresencePersonState` dadurch
   * zum Compilerfehler statt still auf dem falschen Ring zu landen
   * (Gegenstueck zu `PresenceEvaluator.entityState` im Backend, das aus
   * demselben Grund ohne `default` geschrieben ist).
   */
  presenceRingClass(person: PresencePersonStatus): string {
    switch (person.state) {
      case 'on':
        return 'lumina__presence-circle--on';
      case 'off':
        return 'lumina__presence-circle--off';
      case 'unavailable':
      case 'unknown':
        return 'lumina__presence-circle--neutral';
    }
  }

  presenceLabel(person: PresencePersonStatus): string {
    switch (person.state) {
      case 'on':
        return 'Zu Hause';
      case 'off':
        return person.lastSeenAt
          ? `Abwesend seit ${formatDate(person.lastSeenAt, 'HH:mm', 'de')}`
          : 'Abwesend';
      case 'unavailable':
        return 'Keine aktiven Geräte';
      case 'unknown':
        return 'Unbekannt';
    }
  }

  /** Voller Name + Zustand auf Deutsch, fuer `aria-label`/`title` der Kreise. */
  presenceAriaLabel(person: PresencePersonStatus): string {
    return `${person.displayName} • ${this.presenceLabel(person)}`;
  }

  /**
   * Material-Symbol je Zustand fuer die Personenkreise, statt einer Initiale.
   * `unavailable` und `unknown` teilen weiterhin den neutralen Ring
   * (`presenceRingClass`), bekommen hier aber bewusst UNTERSCHIEDLICHE
   * Glyphen: das war vorher nur ueber den fuer das KIOSK-Wandtablet
   * unerreichbaren Tooltip unterscheidbar, jetzt auch auf einen Blick.
   * Exhaustiver `switch` ohne `default` — mit `noImplicitReturns` wird eine
   * kuenftige fuenfte Auspraegung von `PresencePersonState` dadurch zum
   * Compilerfehler statt still auf dem falschen Symbol zu landen (gleiche
   * Absicherung wie `presenceRingClass`/`presenceLabel`).
   */
  presenceIcon(person: PresencePersonStatus): string {
    switch (person.state) {
      case 'on':
        return 'home';
      case 'off':
        return 'directions_walk';
      case 'unavailable':
        return 'signal_disconnected';
      case 'unknown':
        return 'help';
    }
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

  /** Gruppiert nach Kalendertag; einzige Definition in shared/walk-format.util.ts. */
  private groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
    return groupWalks(walks);
  }

  walkTimeRange(walk: TractiveWalk): string {
    return formatWalkTimeRange(walk);
  }

  walkDuration(walk: TractiveWalk): string {
    return formatWalkDuration(walk);
  }

  walkDistance(walk: TractiveWalk): string {
    return formatWalkDistance(walk);
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

