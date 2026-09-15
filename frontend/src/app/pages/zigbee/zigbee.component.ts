import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { Observable, Subscription } from 'rxjs';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import { AuthService } from '../../services/auth.service';
import {
  FlowReference,
  ZigbeeBridgeEvent,
  ZigbeeBridgeStatus,
  ZigbeeDevice,
  ZigbeeHealth,
  ZigbeeLiveEvent,
  ZigbeeMeasurementType
} from '../../models/zigbee.model';
import {
  bridgeEventText,
  healthBadge,
  permitJoinRemainingSeconds,
  silentText,
  sortDevicesForDisplay
} from '../../shared/zigbee-device-view.util';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

export type ZigbeeDialogKind = 'rename' | 'remove' | 'interview' | 'configure' | 'purge';

/**
 * Haelt nur Schluessel (ieeeAddress / DB-Id), nie das Geraeteobjekt: die Liste wird alle
 * 30 s neu geladen, beim Bestaetigen wird das Geraet aus der AKTUELLEN Liste aufgeloest
 * (Regel aus confirmToggle).
 */
export interface ZigbeeDialogState {
  kind: ZigbeeDialogKind;
  friendlyName: string;
  ieeeAddress: string | null;
  deviceId: number | null;
  battery: boolean;
  entityCount: number;
  newName: string;
  references: FlowReference[] | null;
  referencesFailed: boolean;
  busy: boolean;
  error: string | null;
  /** Entfernen ist einmal gescheitert — "Erzwingen" anbieten. */
  offerForce: boolean;
}

const PERMIT_JOIN_SECONDS = 240;
const DEVICES_REFRESH_MS = 30_000;
const EVENTS_VISIBLE_AFTER_CLOSE_MS = 120_000;

/**
 * Zigbee-Geraeteverwaltung: Bridge-Status, Anlernen, Ereignisse, Gerätekarten mit
 * Health-Badge und Entitaeten, Aktionen (ADMIN) und Verlaufschart.
 */
@Component({
  selector: 'app-zigbee',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './zigbee.component.html',
  styleUrl: './zigbee.component.scss'
})
export class ZigbeeComponent implements OnInit, OnDestroy {
  private readonly zigbeeService = inject(ZigbeeService);
  private readonly liveService = inject(ZigbeeLiveService);
  private readonly authService = inject(AuthService);

  readonly isAdmin = this.authService.isAdmin;
  readonly healthBadge = healthBadge;
  readonly silentText = silentText;
  readonly bridgeEventText = bridgeEventText;

  devices: ZigbeeDevice[] = [];
  health: ZigbeeHealth | null = null;
  bridge: ZigbeeBridgeStatus | null = null;
  bridgeEvents: ZigbeeBridgeEvent[] = [];
  permitJoinRemaining = 0;
  permitJoinBusy = false;
  loadError: string | null = null;
  notice: string | null = null;
  menuOpenFor: string | null = null;
  dialog: ZigbeeDialogState | null = null;

  selectedDevice?: string;
  selectedType: ZigbeeMeasurementType = 'TEMPERATURE';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  chartOptions: any = null;

  private readonly subscriptions = new Subscription();
  private devicesSub?: Subscription;
  private historySub?: Subscription;
  private refreshTimer?: ReturnType<typeof setInterval>;
  private countdownTimer?: ReturnType<typeof setInterval>;
  private liveReloadTimer?: ReturnType<typeof setTimeout>;
  private permitJoinClosedAt: number | null = null;
  private initialLoadDone = false;

  ngOnInit(): void {
    this.loadHealth();
    this.loadBridge();
    this.loadBridgeEvents();
    this.loadDevices();
    this.refreshTimer = setInterval(() => { this.loadDevices(); this.loadHealth(); }, DEVICES_REFRESH_MS);
    this.countdownTimer = setInterval(() => this.tickCountdown(), 1000);
    this.subscriptions.add(this.liveService.getLiveStream().subscribe({
      next: (event) => this.applyLiveEvent(event),
      error: () => { /* SSE reconnects via browser */ }
    }));
    this.subscriptions.add(this.liveService.getBridgeEvents().subscribe({
      next: (event) => this.applyBridgeEvent(event),
      error: () => { /* SSE reconnects via browser */ }
    }));
    this.subscriptions.add(this.liveService.getBridgeInfo().subscribe({
      next: (status) => this.applyBridge(status),
      error: () => { /* SSE reconnects via browser */ }
    }));
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.devicesSub?.unsubscribe();
    this.historySub?.unsubscribe();
    clearInterval(this.refreshTimer);
    clearInterval(this.countdownTimer);
    clearTimeout(this.liveReloadTimer);
    this.liveService.disconnect();
  }

  // --- Laden -----------------------------------------------------------------

  /** Fehler bewusst still: ein nicht erreichbarer Health-Endpunkt darf die Seite nicht stoeren. */
  private loadHealth(): void {
    this.zigbeeService.getHealth().subscribe({
      next: (health) => (this.health = health),
      error: () => (this.health = null)
    });
  }

  private loadBridge(): void {
    this.zigbeeService.getBridge().subscribe({
      next: (status) => this.applyBridge(status),
      error: () => { /* Bridge-Status ist Zusatzinfo; die Geraeteliste traegt die Seite */ }
    });
  }

  private loadBridgeEvents(): void {
    this.zigbeeService.getBridgeEvents().subscribe({
      next: (events) => (this.bridgeEvents = events),
      error: () => { /* Ereignisliste ist optional */ }
    });
  }

  /** Nur der Erstabruf meldet einen Fehler; spaetere behalten den letzten Stand. */
  loadDevices(): void {
    this.devicesSub?.unsubscribe();
    this.devicesSub = this.zigbeeService.getDevices().subscribe({
      next: (devices) => {
        this.initialLoadDone = true;
        this.loadError = null;
        this.devices = sortDevicesForDisplay(devices);
        if (!this.selectedDevice && devices.length > 0) {
          this.selectedDevice = this.devices[0].friendlyName;
          this.loadHistory();
        }
      },
      error: (err: Error) => {
        if (!this.initialLoadDone) {
          this.loadError = err.message;
        }
      }
    });
  }

  loadHistory(): void {
    if (!this.selectedDevice) { return; }
    this.historySub?.unsubscribe();
    this.historySub = this.zigbeeService.getMeasurements(this.selectedDevice, this.selectedType).subscribe((measurements) => {
      this.chartOptions = {
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'time' },
        yAxis: { type: 'value' },
        series: [{
          type: 'line',
          showSymbol: false,
          data: measurements.map(m => [m.measuredAt, m.value])
        }]
      };
    });
  }

  private applyBridge(status: ZigbeeBridgeStatus): void {
    const wasOpen = this.bridge?.permitJoin ?? false;
    this.bridge = status;
    if (wasOpen && !status.permitJoin) {
      this.permitJoinClosedAt = Date.now();
    }
    this.tickCountdown();
  }

  private applyBridgeEvent(event: ZigbeeBridgeEvent): void {
    this.bridgeEvents = [event, ...this.bridgeEvents].slice(0, 50);
    // Ein beigetretenes oder fertig interviewtes Geraet soll sofort als Karte erscheinen
    this.scheduleLiveReload();
  }

  private applyLiveEvent(event: ZigbeeLiveEvent): void {
    const device = this.devices.find(d => d.friendlyName === event.friendlyName);
    if (device) {
      if (event.batteryPercent != null) { device.lastBatteryPercent = event.batteryPercent; }
      if (event.linkQuality != null) { device.lastLinkQuality = event.linkQuality; }
      device.lastSeen = event.measuredAt;
    }
    this.scheduleLiveReload();
  }

  /** Entprellt: viele Sensoren melden im Minutentakt, nicht bei jedem Event neu laden. */
  private scheduleLiveReload(): void {
    clearTimeout(this.liveReloadTimer);
    this.liveReloadTimer = setTimeout(() => this.loadDevices(), 2000);
  }

  private tickCountdown(): void {
    this.permitJoinRemaining = this.bridge?.permitJoin
      ? permitJoinRemainingSeconds(this.bridge.permitJoinEnd, Date.now())
      : 0;
  }

  // --- Anzeige-Helfer ---------------------------------------------------------

  get permitJoinActive(): boolean {
    return (this.bridge?.permitJoin ?? false) && this.permitJoinRemaining > 0;
  }

  /** Ausgeklappt waehrend des Anlernens und 2 min danach. */
  get eventsExpanded(): boolean {
    if (this.permitJoinActive) { return true; }
    return this.permitJoinClosedAt != null && Date.now() - this.permitJoinClosedAt < EVENTS_VISIBLE_AFTER_CLOSE_MS;
  }

  get showAvailabilityHint(): boolean {
    return this.bridge?.availabilityCheckEnabled === false;
  }

  /**
   * Zweite Kopie der Backend-Schwellen (zigbee.device-health.*), wie die 50-W-Schwelle des
   * Modus-Checks: reine Anzeige-Daempfung je Entitaet, das Urteil je Geraet kommt vom Server.
   * Wer die Properties aendert, zieht diese Zahlen nach.
   */
  entityStale(device: ZigbeeDevice, lastUpdated: string): boolean {
    const thresholdMs = (device.battery ? 25 * 3600 : 15 * 60) * 1000;
    const updated = Date.parse(lastUpdated);
    return !isNaN(updated) && Date.now() - updated > thresholdMs;
  }

  toggleMenu(ieeeAddress: string | null): void {
    this.menuOpenFor = this.menuOpenFor === ieeeAddress ? null : ieeeAddress;
  }

  // --- Anlernen ---------------------------------------------------------------

  openPermitJoin(): void {
    this.runPermitJoin(this.zigbeeService.openPermitJoin(PERMIT_JOIN_SECONDS));
  }

  closePermitJoin(): void {
    this.runPermitJoin(this.zigbeeService.closePermitJoin());
  }

  private runPermitJoin(action: Observable<void>): void {
    this.permitJoinBusy = true;
    this.notice = null;
    action.subscribe({
      next: () => { this.permitJoinBusy = false; this.loadBridge(); },
      error: (err: Error) => { this.permitJoinBusy = false; this.notice = err.message; }
    });
  }

  // --- Dialoge ----------------------------------------------------------------

  openDialog(kind: ZigbeeDialogKind, device: ZigbeeDevice): void {
    this.menuOpenFor = null;
    this.notice = null;
    this.dialog = {
      kind,
      friendlyName: device.friendlyName,
      ieeeAddress: device.ieeeAddress,
      deviceId: device.id,
      battery: device.battery,
      entityCount: device.entities.length,
      newName: device.friendlyName,
      references: null,
      referencesFailed: false,
      busy: false,
      error: null,
      offerForce: false
    };
    if (kind === 'rename' || kind === 'remove' || kind === 'purge') {
      this.loadReferences(this.dialog);
    }
  }

  /** "Benennen" aus der Ereignisliste: das Geraet steht evtl. noch nicht in devices. */
  openRenameForEvent(event: ZigbeeBridgeEvent): void {
    if (!event.ieeeAddress) { return; }
    const known = this.devices.find(d => d.ieeeAddress === event.ieeeAddress);
    this.openDialog('rename', known ?? {
      id: null, friendlyName: event.friendlyName ?? event.ieeeAddress, ieeeAddress: event.ieeeAddress,
      knownToBridge: true, type: null, powerSource: null, battery: true, interviewCompleted: true,
      supported: null, model: event.model, vendor: event.vendor, description: null,
      lastBatteryPercent: null, lastLinkQuality: null, lastSeen: null,
      health: { status: 'UNKNOWN', basis: null, lastHeardAt: null, silentSeconds: null }, entities: []
    });
  }

  /** Beim Oeffnen geladen; ein Abruffehler wird gezeigt, nie als "keine Flows" gewertet. */
  private loadReferences(dialog: ZigbeeDialogState): void {
    this.zigbeeService.getFlowReferences(dialog.friendlyName).subscribe({
      next: (refs) => { if (this.dialog === dialog) { dialog.references = refs; } },
      error: () => { if (this.dialog === dialog) { dialog.referencesFailed = true; } }
    });
  }

  closeDialog(): void {
    if (this.dialog?.busy) { return; }
    this.dialog = null;
  }

  /**
   * Loest das Geraet aus der aktuellen Liste neu auf. Ist es verschwunden (Refresh
   * waehrend der Dialog offen war), passiert nichts — ein Fehlgriff auf ein anderes
   * Geraet waere schlimmer als ein abgebrochener Dialog.
   */
  confirmDialog(force = false): void {
    const dialog = this.dialog;
    if (!dialog || dialog.busy) { return; }
    const current = this.resolveDialogDevice(dialog);
    if (!current) {
      this.dialog = null;
      this.notice = `„${dialog.friendlyName}" ist nicht mehr in der Liste — nichts geändert.`;
      return;
    }
    const action = this.buildAction(dialog, current, force);
    if (!action) { return; }
    dialog.busy = true;
    dialog.error = null;
    action.subscribe({
      next: () => {
        dialog.busy = false;
        this.dialog = null;
        this.loadDevices();
        this.loadBridge();
      },
      error: (err: Error) => {
        dialog.busy = false;
        dialog.error = err.message;
        if (dialog.kind === 'remove' && !force) {
          dialog.offerForce = true;
        }
      }
    });
  }

  private resolveDialogDevice(dialog: ZigbeeDialogState): ZigbeeDevice | undefined {
    if (dialog.kind === 'purge') {
      return this.devices.find(d => d.id != null && d.id === dialog.deviceId && !d.knownToBridge);
    }
    return this.devices.find(d => d.ieeeAddress != null && d.ieeeAddress === dialog.ieeeAddress && d.knownToBridge);
  }

  private buildAction(dialog: ZigbeeDialogState, device: ZigbeeDevice, force: boolean): Observable<unknown> | null {
    switch (dialog.kind) {
      case 'rename': {
        const name = dialog.newName.trim();
        if (!name || name === device.friendlyName) {
          dialog.error = 'Bitte einen neuen Namen eingeben.';
          return null;
        }
        return this.zigbeeService.renameDevice(device.ieeeAddress!, name);
      }
      case 'interview':
        return this.zigbeeService.interviewDevice(device.ieeeAddress!);
      case 'configure':
        return this.zigbeeService.configureDevice(device.ieeeAddress!);
      case 'remove':
        return this.zigbeeService.removeDevice(device.ieeeAddress!, force);
      case 'purge':
        return this.zigbeeService.purgeLocalData(device.id!);
    }
  }

  dialogTitle(kind: ZigbeeDialogKind): string {
    switch (kind) {
      case 'rename': return 'Gerät umbenennen';
      case 'interview': return 'Neu interviewen?';
      case 'configure': return 'Neu konfigurieren?';
      case 'remove': return 'Aus zigbee2mqtt entfernen?';
      case 'purge': return 'Aus Household Manager entfernen?';
    }
  }

  dialogConfirmLabel(kind: ZigbeeDialogKind): string {
    switch (kind) {
      case 'rename': return 'Umbenennen';
      case 'interview': return 'Interview starten';
      case 'configure': return 'Konfigurieren';
      case 'remove': return 'Entfernen';
      case 'purge': return 'Endgültig löschen';
    }
  }
}
