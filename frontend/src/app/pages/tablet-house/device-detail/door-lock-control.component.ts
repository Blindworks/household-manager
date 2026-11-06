import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, Output, inject
} from '@angular/core';
import { DetailControl } from '../../../shared/house/device-detail.util';
import { HouseDeviceControlService } from '../house-device-control.service';
import { UNREACHABLE } from './detail-texts';

type LockControl = Extract<DetailControl, { type: 'lock' }>;

/**
 * Verriegeln-Knopf der Haustuer im Detail-Feld.
 *
 * Bewusst nur Verriegeln: Entriegeln und Tuer oeffnen gibt es im Feld nicht, auch nicht
 * ausgegraut. Der Knopf erscheint nur, wenn die Tuer sicher nicht verriegelt ist; ohne
 * Dialog, weil Verriegeln harmlos ist. Der Dienst liest den Zustand vorher frisch.
 */
@Component({
  selector: 'app-door-lock-control',
  standalone: true,
  templateUrl: './door-lock-control.component.html',
  styleUrl: './door-lock-control.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DoorLockControlComponent {
  private readonly deviceControl = inject(HouseDeviceControlService);
  private readonly cdr = inject(ChangeDetectorRef);

  @Input({ required: true }) control!: LockControl;
  /** Das Verriegeln hat den Zustand geaendert oder gezeigt, dass der angezeigte Stand veraltet ist. */
  @Output() readonly changed = new EventEmitter<void>();

  pending = false;
  error: string | null = null;

  lock(): void {
    if (this.pending || this.control.locked !== false) {
      return;
    }
    this.pending = true;
    this.error = null;
    this.deviceControl.lockDoor(this.control.smartlockId).subscribe({
      next: outcome => {
        this.pending = false;
        if (outcome === 'unavailable') {
          this.error = UNREACHABLE;
        } else {
          this.changed.emit();
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.pending = false;
        this.error = UNREACHABLE;
        this.cdr.markForCheck();
      }
    });
  }
}
