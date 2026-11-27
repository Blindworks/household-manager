import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, interval, startWith, switchMap } from 'rxjs';
import { TeslaService } from '../../services/tesla.service';
import { TeslaCommand, TeslaStatus, TeslaVehicle } from '../../models/tesla.model';
import {
  TeslaButton,
  TeslaStatusItem,
  teslaBatteryTone,
  teslaButtons,
  teslaCommandStillApplies,
  teslaPaint,
  teslaRangeLabel,
  teslaStaleLabel,
  teslaStatusItems
} from '../../shared/tesla-view.util';

/**
 * Tesla-Kachel im Dashboard (Tessie): Fahrzeugbild, Akku, Reichweite, Ladezustand und vier Knöpfe.
 * Das Bild kommt über das Backend (eigene Adresse oder Teslas Konfigurator); lädt es nicht,
 * steht die Zeichnung da.
 * Holt den Stand selbst im 60-s-Takt; ein fehlgeschlagener Abruf behält den letzten Stand.
 * Ohne eingerichteten Tessie-Zugang bleibt die Kachel unsichtbar.
 *
 * Eigene Styles, Farben nur über lumina-Tokens (steht im .lumina-Wurzelelement).
 */
@Component({
  selector: 'app-tesla-card',
  standalone: true,
  templateUrl: './tesla-card.component.html',
  styleUrls: ['./tesla-card.component.scss']
})
export class TeslaCardComponent implements OnInit {
  static readonly REFRESH_MS = 60_000;

  private readonly service = inject(TeslaService);
  private readonly destroyRef = inject(DestroyRef);

  status: TeslaStatus | null = null;
  /** Befehl, der gerade läuft (Tessie wartet, bis das Auto ihn ausgeführt hat). */
  busyCommand: TeslaCommand | null = null;
  /** Entriegeln wartet auf die Bestätigung. */
  confirming = false;
  commandError: string | null = null;
  now = new Date();
  /** Bildversion, die nicht geladen werden konnte — dann bleibt es bei der Zeichnung. */
  failedImageVersion: string | null = null;

  get vehicle(): TeslaVehicle | undefined {
    return this.status?.configured ? this.status.vehicle : undefined;
  }

  get visible(): boolean {
    return !!this.status?.configured && (!!this.status.vehicle || !!this.status.error);
  }

  get title(): string {
    return this.vehicle?.displayName || 'Tesla';
  }

  get batteryLevel(): number | undefined {
    return this.vehicle?.batteryLevel;
  }

  get batteryTone(): string {
    return teslaBatteryTone(this.batteryLevel);
  }

  get rangeLabel(): string {
    return teslaRangeLabel(this.vehicle?.rangeKm);
  }

  get statusItems(): TeslaStatusItem[] {
    return this.vehicle ? teslaStatusItems(this.vehicle) : [];
  }

  get buttons(): TeslaButton[] {
    return this.vehicle ? teslaButtons(this.vehicle) : [];
  }

  get staleLabel(): string | null {
    return teslaStaleLabel(this.vehicle?.measuredAt, this.now);
  }

  /** Echtes Fahrzeugbild, solange es eines gibt und es sich laden ließ. */
  get imageUrl(): string | null {
    const version = this.status?.imageVersion;
    if (!version || version === this.failedImageVersion) {
      return null;
    }
    return this.service.imageUrl(version);
  }

  onImageError(): void {
    this.failedImageVersion = this.status?.imageVersion ?? null;
  }

  get paint(): string {
    return teslaPaint(this.vehicle?.exteriorColor);
  }

  ngOnInit(): void {
    interval(TeslaCardComponent.REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.service.getStatus().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(status => this.adopt(status));
  }

  press(button: TeslaButton): void {
    if (this.busyCommand) {
      return;
    }
    this.commandError = null;
    if (button.confirm) {
      this.confirming = true;
      return;
    }
    this.send(button.command);
  }

  cancelConfirm(): void {
    this.confirming = false;
  }

  /** Entriegeln erst nach frischem Abruf: ist das Auto inzwischen offen, wird nichts gesendet. */
  confirmUnlock(): void {
    if (this.busyCommand) {
      return;
    }
    this.confirming = false;
    this.busyCommand = 'UNLOCK';
    this.service.getStatus().subscribe({
      next: status => {
        this.adopt(status);
        if (teslaCommandStillApplies(this.vehicle, 'UNLOCK')) {
          this.busyCommand = null;
          this.send('UNLOCK');
        } else {
          this.busyCommand = null;
        }
      },
      error: (error: Error) => this.fail(error)
    });
  }

  private send(command: TeslaCommand): void {
    if (!teslaCommandStillApplies(this.vehicle, command)) {
      return;
    }
    this.busyCommand = command;
    this.service.sendCommand(command).subscribe({
      next: status => {
        this.busyCommand = null;
        this.adopt(status);
      },
      error: (error: Error) => this.fail(error)
    });
  }

  private fail(error: Error): void {
    this.busyCommand = null;
    this.commandError = error.message;
  }

  private adopt(status: TeslaStatus): void {
    this.status = status;
    this.now = new Date();
  }
}
