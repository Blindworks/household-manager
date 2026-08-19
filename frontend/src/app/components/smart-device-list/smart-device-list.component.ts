import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { catchError, forkJoin, of } from 'rxjs';
import { SmartDeviceService } from '../../services/smart-device.service';
import { LightStateRequest, SmartDevice } from '../../models/smart-device.model';
import { hexToHueSaturation, hueSaturationToHex } from '../../shared/color-conversion.util';

export type DeviceViewMode = 'normal' | 'compact';

/**
 * Geraetespezifischer, lokal gehaltener Stand der Licht-Regler. Wird aus dem Ist-Zustand des
 * Geraets vorbelegt (`SmartDevice.brightness`/`hue`/`saturation`/`colorTemp`) und bei jeder
 * Aktualisierung des Geraets neu vorbelegt (siehe `seedLightState`) - nur waehrend der Nutzer aktiv
 * an einem Regler zieht (zwischen `input` und `change`) bzw. waehrend ein Request noch laeuft, ist
 * dieser lokale Stand die massgebliche Anzeige. Bleibt bei einem fehlgeschlagenen Request
 * unveraendert stehen, statt auf einen Default zurueckzuspringen.
 *
 * Meldet das Geraet einen Wert nicht (kein Feld im JSON, siehe `SmartDevice`), zeigt der
 * `*Known`-Flag das an - der Regler startet dann bei einem allgemeinen Default, aber die Kachel
 * darf das nicht als echten Geraetewert ausgeben.
 */
export interface LightControlState {
  brightness: number;
  brightnessKnown: boolean;
  colorTemp: number;
  colorTempKnown: boolean;
  colorHex: string;
  colorKnown: boolean;
  pending: boolean;
  error: string | null;
}

/** Zustand des inline "IP setzen"-Formulars je Tapo-Geraet. */
export interface TapoAddressFormState {
  visible: boolean;
  ip: string;
  pending: boolean;
  error: string | null;
}

/**
 * Wiederverwendbare Geraeteliste aus der smart_devices-Datenbank
 * mit Schalten, Status-Refresh und manuellem Rescan (gesamt oder je Typ).
 */
@Component({
  selector: 'app-smart-device-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './smart-device-list.component.html',
  styleUrl: './smart-device-list.component.scss'
})
export class SmartDeviceListComponent implements OnInit, OnDestroy {
  private static readonly VIEW_MODE_STORAGE_KEY = 'smartDeviceViewMode';
  private static readonly SUCCESS_MESSAGE_TIMEOUT_MS = 3000;
  private static readonly DEFAULT_BRIGHTNESS = 100;
  private static readonly DEFAULT_COLOR_TEMP_MIN = 2500;
  private static readonly DEFAULT_COLOR_TEMP_MAX = 6500;
  private static readonly DEFAULT_COLOR_HEX = '#ffffff';

  private readonly smartDeviceService = inject(SmartDeviceService);
  private readonly typeOrder: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];
  readonly scanTypes: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];

  devices: SmartDevice[] = [];
  isLoading = true;
  isScanning = false;
  scanningType: SmartDevice['deviceType'] | 'ALL' | null = null;
  errorMessage: string | null = null;
  togglingDevices = new Set<number>();
  viewMode: DeviceViewMode = 'normal';

  /** Geraet, dessen Ausschalten gerade bestaetigt werden muss; null = kein Dialog offen. */
  confirmOffDevice: SmartDevice | null = null;

  // Kasa per IP hinzufuegen
  showAddKasaForm = false;
  kasaIpInput = '';
  isAddingKasaDevice = false;
  addKasaError: string | null = null;
  addKasaSuccessMessage: string | null = null;
  private addKasaSuccessTimeout: ReturnType<typeof setTimeout> | null = null;

  // Licht-Regler (Helligkeit/Farbe/Farbtemperatur) je Geraet, lazy angelegt
  private readonly lightStates = new Map<number, LightControlState>();

  // "IP setzen"-Formular je Tapo-Geraet, lazy angelegt
  private readonly addressForms = new Map<number, TapoAddressFormState>();

  ngOnInit(): void {
    this.viewMode = this.readStoredViewMode();
    this.loadDevices();
  }

  ngOnDestroy(): void {
    this.clearAddKasaSuccessTimer();
  }

  setViewMode(mode: DeviceViewMode): void {
    if (this.viewMode === mode) {
      return;
    }
    this.viewMode = mode;
    this.persistViewMode(mode);
  }

  private readStoredViewMode(): DeviceViewMode {
    try {
      const stored = localStorage.getItem(SmartDeviceListComponent.VIEW_MODE_STORAGE_KEY);
      return stored === 'compact' ? 'compact' : 'normal';
    } catch {
      // localStorage nicht verfügbar (z. B. Private Mode) - Standard verwenden
      return 'normal';
    }
  }

  private persistViewMode(mode: DeviceViewMode): void {
    try {
      localStorage.setItem(SmartDeviceListComponent.VIEW_MODE_STORAGE_KEY, mode);
    } catch {
      // Persistenz optional - Fehler still ignorieren
    }
  }

  loadDevices(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.smartDeviceService.getAllDevices().subscribe({
      next: (devices) => {
        this.devices = devices;
        devices.forEach(device => this.seedLightState(device));
        this.isLoading = false;

        // Automatisch den aktuellen Status aller Geräte im Hintergrund aktualisieren
        setTimeout(() => {
          this.refreshAllDevicesInBackground();
        }, 500);
      },
      error: (error: Error) => {
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  scanType(type: SmartDevice['deviceType']): void {
    this.isScanning = true;
    this.scanningType = type;
    this.errorMessage = null;
    this.smartDeviceService.scanDevices(type).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: (error: Error) => {
        this.errorMessage = `Rescan (${this.getTypeLabel(type)}) fehlgeschlagen: ${error.message}`;
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  scanAllDeviceTypes(): void {
    this.isScanning = true;
    this.scanningType = 'ALL';
    this.errorMessage = null;

    const scanRequests = this.scanTypes.map(type =>
      this.smartDeviceService.scanDevices(type).pipe(
        catchError(() => of([] as SmartDevice[]))
      )
    );

    forkJoin(scanRequests).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  toggleAddKasaForm(): void {
    if (this.showAddKasaForm) {
      this.cancelAddKasaForm();
      return;
    }
    this.showAddKasaForm = true;
    this.addKasaError = null;
    this.addKasaSuccessMessage = null;
    this.clearAddKasaSuccessTimer();
  }

  cancelAddKasaForm(): void {
    this.showAddKasaForm = false;
    this.kasaIpInput = '';
    this.addKasaError = null;
    this.addKasaSuccessMessage = null;
    this.clearAddKasaSuccessTimer();
  }

  submitAddKasaForm(): void {
    if (this.isAddingKasaDevice) {
      return;
    }

    const ip = this.kasaIpInput.trim();
    if (!ip) {
      this.addKasaError = 'Bitte eine IP-Adresse eingeben.';
      return;
    }
    if (!this.isValidIpv4(ip)) {
      this.addKasaError = 'Ungueltige IP-Adresse. Bitte z. B. 192.168.1.116 eingeben.';
      return;
    }

    this.isAddingKasaDevice = true;
    this.addKasaError = null;

    this.smartDeviceService.addKasaDeviceByIp(ip).subscribe({
      next: () => {
        this.isAddingKasaDevice = false;
        this.showAddKasaForm = false;
        this.kasaIpInput = '';
        this.loadDevices();
        this.showAddKasaSuccess('Kasa-Geraet wurde hinzugefuegt.');
      },
      error: (error: Error) => {
        // Eingabe bleibt bei einem Fehler erhalten, damit die IP nicht erneut getippt werden muss
        this.addKasaError = error.message;
        this.isAddingKasaDevice = false;
      }
    });
  }

  private isValidIpv4(value: string): boolean {
    const octets = value.split('.');
    if (octets.length !== 4) {
      return false;
    }
    return octets.every(octet => this.isValidIpv4Octet(octet));
  }

  private isValidIpv4Octet(octet: string): boolean {
    if (!/^\d{1,3}$/.test(octet)) {
      return false;
    }
    // Fuehrende Nullen (z. B. "010") wertet Java als Oktalzahl/Hostname statt als Dezimal-Oktett
    // und loest einen langsamen, verwirrenden DNS-Lookup statt eines klaren 400 aus - hier schon ablehnen.
    if (octet.length > 1 && octet.startsWith('0')) {
      return false;
    }
    return Number(octet) <= 255;
  }

  private showAddKasaSuccess(message: string): void {
    this.clearAddKasaSuccessTimer();
    this.addKasaSuccessMessage = message;
    this.addKasaSuccessTimeout = setTimeout(() => {
      this.addKasaSuccessMessage = null;
      this.addKasaSuccessTimeout = null;
    }, SmartDeviceListComponent.SUCCESS_MESSAGE_TIMEOUT_MS);
  }

  private clearAddKasaSuccessTimer(): void {
    if (this.addKasaSuccessTimeout !== null) {
      clearTimeout(this.addKasaSuccessTimeout);
      this.addKasaSuccessTimeout = null;
    }
  }

  refreshAllDevices(): void {
    let failCount = 0;

    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          this.updateDeviceInList(updatedDevice);
        },
        error: (error: Error) => {
          failCount++;
          if (failCount === 1) {
            // Zeige nur beim ersten Fehler eine Meldung
            this.errorMessage = `Fehler beim Aktualisieren von ${device.deviceName}: ${error.message}`;
          }
        }
      });
    });
  }

  /**
   * Schaltet ein Geraet. Geschuetzte Geraete oeffnen beim AUSschalten erst den
   * Bestaetigungsdialog; Einschalten laeuft immer direkt - ein versehentliches
   * Einschalten ist harmlos, ein versehentliches Ausschalten (Kuehlschrank, Router) nicht.
   */
  toggleDevice(device: SmartDevice): void {
    if (!device.isOnline || this.isDeviceToggling(device.id)) {
      return;
    }
    if (device.confirmRequired && device.isPoweredOn) {
      this.confirmOffDevice = device;
      return;
    }
    this.executeToggle(device);
  }

  /** Bestaetigung im Dialog: schliesst ihn und schaltet aus. */
  confirmTurnOff(): void {
    const device = this.confirmOffDevice;
    this.confirmOffDevice = null;
    if (device) {
      this.executeToggle(device);
    }
  }

  closeConfirmOffDialog(): void {
    this.confirmOffDevice = null;
  }

  private executeToggle(device: SmartDevice): void {
    this.togglingDevices.add(device.id);
    const action = device.isPoweredOn
      ? this.smartDeviceService.turnOff(device.id)
      : this.smartDeviceService.turnOn(device.id);

    action.subscribe({
      next: () => {
        device.isPoweredOn = !device.isPoweredOn;
        this.togglingDevices.delete(device.id);
      },
      error: (error: Error) => {
        this.errorMessage = `Fehler beim Schalten von ${device.deviceName}: ${error.message}`;
        this.togglingDevices.delete(device.id);
      }
    });
  }

  isDeviceToggling(deviceId: number): boolean {
    return this.togglingDevices.has(deviceId);
  }

  // ==================== Licht-Regler (Helligkeit/Farbe/Farbtemperatur) ====================

  hasCapability(device: SmartDevice, capability: string): boolean {
    return device.capabilities?.includes(capability) ?? false;
  }

  hasLightControls(device: SmartDevice): boolean {
    return this.hasCapability(device, 'BRIGHTNESS')
      || this.hasCapability(device, 'COLOR')
      || this.hasCapability(device, 'COLOR_TEMP');
  }

  /** Geraetespezifischer zulaessiger Farbtemperatur-Bereich, mit Fallback auf 2500-6500K. */
  getColorTempRange(device: SmartDevice): { min: number; max: number } {
    const min = this.asFiniteNumber(device.metadata?.['colorTempRangeMin']);
    const max = this.asFiniteNumber(device.metadata?.['colorTempRangeMax']);
    if (min !== null && max !== null && min < max) {
      return { min, max };
    }
    return {
      min: SmartDeviceListComponent.DEFAULT_COLOR_TEMP_MIN,
      max: SmartDeviceListComponent.DEFAULT_COLOR_TEMP_MAX
    };
  }

  /** Liefert den lokalen Reglerstand eines Geraets, belegt ihn beim ersten Zugriff aus dem Geraet vor. */
  getLightState(device: SmartDevice): LightControlState {
    let state = this.lightStates.get(device.id);
    if (!state) {
      this.seedLightState(device);
      state = this.lightStates.get(device.id)!;
    }
    return state;
  }

  /**
   * (Neu-)Belegt den lokalen Reglerstand eines Geraets aus dessen Ist-Zustand. Aufgerufen beim
   * initialen Laden und aus `updateDeviceInList` - also bei jedem Refresh, inklusive dem, den ein
   * erfolgreiches Setzen selbst ausloest (der Endpunkt liefert den aktualisierten Geraetezustand
   * zurueck). Ein laufender Request oder eine bereits angezeigte Fehlermeldung werden dabei nicht
   * angetastet - die Werte werden neu vorbelegt, der Interaktionszustand nicht.
   */
  private seedLightState(device: SmartDevice): void {
    const existing = this.lightStates.get(device.id);
    const range = this.getColorTempRange(device);

    const brightnessKnown = this.isKnownNumber(device.brightness);
    const colorTempKnown = this.isKnownNumber(device.colorTemp);
    const colorKnown = this.isKnownNumber(device.hue) && this.isKnownNumber(device.saturation);

    this.lightStates.set(device.id, {
      brightness: brightnessKnown ? device.brightness! : SmartDeviceListComponent.DEFAULT_BRIGHTNESS,
      brightnessKnown,
      colorTemp: colorTempKnown ? device.colorTemp! : Math.round((range.min + range.max) / 2),
      colorTempKnown,
      colorHex: colorKnown ? hueSaturationToHex(device.hue!, device.saturation!) : SmartDeviceListComponent.DEFAULT_COLOR_HEX,
      colorKnown,
      pending: existing?.pending ?? false,
      error: existing?.error ?? null
    });
  }

  private isKnownNumber(value: number | undefined | null): value is number {
    return value !== undefined && value !== null;
  }

  onBrightnessInput(device: SmartDevice, event: Event): void {
    const state = this.getLightState(device);
    state.brightness = this.numberFromInput(event);
    // Ab der ersten aktiven Bedienung ist der Wert die bewusste Wahl des Nutzers, kein Default mehr.
    state.brightnessKnown = true;
    state.error = null;
  }

  onBrightnessCommit(device: SmartDevice): void {
    const state = this.getLightState(device);
    this.commitLightState(device, { brightness: state.brightness });
  }

  onColorTempInput(device: SmartDevice, event: Event): void {
    const state = this.getLightState(device);
    state.colorTemp = this.numberFromInput(event);
    state.colorTempKnown = true;
    state.error = null;
  }

  onColorTempCommit(device: SmartDevice): void {
    const state = this.getLightState(device);
    this.commitLightState(device, { colorTemp: state.colorTemp });
  }

  onColorInput(device: SmartDevice, event: Event): void {
    const state = this.getLightState(device);
    state.colorHex = (event.target as HTMLInputElement).value;
    state.colorKnown = true;
    state.error = null;
  }

  onColorCommit(device: SmartDevice): void {
    const state = this.getLightState(device);
    const { hue, saturation } = hexToHueSaturation(state.colorHex);
    this.commitLightState(device, { hue, saturation });
  }

  private commitLightState(device: SmartDevice, request: LightStateRequest): void {
    const state = this.getLightState(device);
    if (state.pending) {
      return;
    }
    state.pending = true;
    state.error = null;

    this.smartDeviceService.setLightState(device.id, request).subscribe({
      next: (updatedDevice) => {
        state.pending = false;
        this.updateDeviceInList(updatedDevice);
      },
      error: (error: Error) => {
        state.pending = false;
        state.error = error.message;
      }
    });
  }

  private numberFromInput(event: Event): number {
    return Number((event.target as HTMLInputElement).value);
  }

  private asFiniteNumber(value: unknown): number | null {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric : null;
  }

  // ==================== Tapo-Adresse manuell setzen ====================

  /** Liefert den Formularstand eines Tapo-Geraets, legt ihn beim ersten Zugriff an. */
  getAddressForm(device: SmartDevice): TapoAddressFormState {
    let state = this.addressForms.get(device.id);
    if (!state) {
      state = { visible: false, ip: device.ipAddress ?? '', pending: false, error: null };
      this.addressForms.set(device.id, state);
    }
    return state;
  }

  toggleAddressForm(device: SmartDevice): void {
    const state = this.getAddressForm(device);
    state.visible = !state.visible;
    state.error = null;
    if (state.visible) {
      state.ip = device.ipAddress ?? '';
    }
  }

  onAddressInput(device: SmartDevice, event: Event): void {
    const state = this.getAddressForm(device);
    state.ip = (event.target as HTMLInputElement).value;
    state.error = null;
  }

  submitAddressForm(device: SmartDevice): void {
    const state = this.getAddressForm(device);
    if (state.pending) {
      return;
    }

    const ip = state.ip.trim();
    if (!ip) {
      state.error = 'Bitte eine IP-Adresse eingeben.';
      return;
    }
    if (!this.isValidIpv4(ip)) {
      state.error = 'Ungueltige IP-Adresse. Bitte z. B. 192.168.1.114 eingeben.';
      return;
    }

    state.pending = true;
    state.error = null;

    this.smartDeviceService.setTapoAddress(device.id, ip).subscribe({
      next: () => {
        state.pending = false;
        state.visible = false;
        this.loadDevices();
      },
      error: (error: Error) => {
        state.pending = false;
        state.error = error.message;
      }
    });
  }

  /** Flache, nach Typ-Reihenfolge sortierte Liste - für die Kompakt-Ansicht ohne Kategorien. */
  get orderedDevices(): SmartDevice[] {
    return this.groupedDevices.flatMap(group => group.devices);
  }

  get groupedDevices(): Array<{ type: SmartDevice['deviceType']; label: string; devices: SmartDevice[] }> {
    return this.typeOrder
      .map(type => ({
        type,
        label: this.getTypeLabel(type),
        devices: this.devices.filter(device => device.deviceType === type)
      }))
      .filter(group => group.devices.length > 0);
  }

  getTypeLabel(type: SmartDevice['deviceType']): string {
    switch (type) {
      case 'KASA':
        return 'Kasa';
      case 'TAPO':
        return 'Tapo';
      case 'MEROSS':
        return 'Meross';
      default:
        return type;
    }
  }

  getStatusText(device: SmartDevice): string {
    if (!device.isOnline) {
      return 'Offline';
    }
    return device.isPoweredOn ? 'Online - An' : 'Online - Aus';
  }

  getStatusClass(device: SmartDevice): string {
    if (!device.isOnline) {
      return 'device-card__status--offline';
    }
    return device.isPoweredOn ? 'device-card__status--on' : 'device-card__status--standby';
  }

  trackByDeviceId(_: number, device: SmartDevice): number {
    return device.id;
  }

  dismissError(): void {
    this.errorMessage = null;
  }

  private updateDeviceInList(updatedDevice: SmartDevice): void {
    const index = this.devices.findIndex(d => d.id === updatedDevice.id);
    if (index !== -1) {
      this.devices[index] = updatedDevice;
    }
    // Jeder Refresh (inklusive dem nach einem erfolgreichen Licht-Set) bringt den echten
    // Geraetezustand mit - die Regler damit neu vorbelegen statt auf dem alten Stand zu bleiben.
    this.seedLightState(updatedDevice);
  }

  private refreshAllDevicesInBackground(): void {
    this.devices.forEach(device => {
      this.smartDeviceService.refreshDeviceState(device.id).subscribe({
        next: (updatedDevice) => {
          this.updateDeviceInList(updatedDevice);
        },
        error: () => {
          // Stille Fehler - kein Error-Banner für Hintergrund-Updates
        }
      });
    });
  }
}
