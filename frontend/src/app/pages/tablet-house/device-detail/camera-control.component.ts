import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output,
  SimpleChanges, inject
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { DetailControl } from '../../../shared/house/device-detail.util';
import { HouseDeviceControlService } from '../house-device-control.service';
import { CAMERA_UNREACHABLE, SNAPSHOT_FAILED, UNREACHABLE } from './detail-texts';

type CameraControl = Extract<DetailControl, { type: 'camera' }>;

/**
 * Kamera im Detail-Feld: Standbild, Bewegungserkennung, Schnappschuss.
 *
 * Das Bild haengt an `cacheKey`, der sich nur nach einem erfolgreichen Schnappschuss
 * aendert - ein neues `control` aus dem Takt laesst es unberuehrt (kein Flackern).
 * Antworten einer frueheren Kamera werden verworfen.
 */
@Component({
  selector: 'app-camera-control',
  standalone: true,
  templateUrl: './camera-control.component.html',
  styleUrl: './camera-control.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CameraControlComponent implements OnChanges {
  private readonly deviceControl = inject(HouseDeviceControlService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  @Input({ required: true }) control!: CameraControl;
  /** Eine Aktion hat den Kamerazustand geaendert oder gezeigt, dass der angezeigte Stand veraltet ist. */
  @Output() readonly changed = new EventEmitter<void>();

  readonly cameraUnreachable = CAMERA_UNREACHABLE;

  cacheKey = Date.now();
  imageFailed = false;
  confirmingOff = false;
  armPending = false;
  snapshotPending = false;
  error: string | null = null;

  private cameraId: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['control']) {
      return;
    }
    if (this.cameraId !== null && this.cameraId !== this.control.cameraId) {
      this.cacheKey = Date.now();
      this.imageFailed = false;
      this.confirmingOff = false;
      this.armPending = false;
      this.snapshotPending = false;
      this.error = null;
    }
    this.cameraId = this.control.cameraId;
  }

  get imageUrl(): string {
    return this.deviceControl.thumbnailUrl(this.control.cameraId, this.cacheKey);
  }

  onSwitch(): void {
    const camera = this.control.camera;
    if (!camera || this.armPending) {
      return;
    }
    if (camera.armed) {
      this.confirmingOff = true;
      return;
    }
    this.sendArmed(true);
  }

  cancelOff(): void {
    this.confirmingOff = false;
  }

  confirmOff(): void {
    this.confirmingOff = false;
    // Der Dienst liest die Kamera frisch und schaltet nur, wenn sie noch aktiv ist.
    this.sendArmed(false);
  }

  snapshot(): void {
    if (this.snapshotPending) {
      return;
    }
    const id = this.control.cameraId;
    this.snapshotPending = true;
    this.error = null;
    this.guarded(this.deviceControl.takeSnapshot(id), id, {
      next: () => {
        this.snapshotPending = false;
        this.cacheKey = Date.now();
        this.imageFailed = false;
      },
      error: () => {
        this.snapshotPending = false;
        this.error = SNAPSHOT_FAILED;
      }
    });
  }

  private sendArmed(armed: boolean): void {
    const id = this.control.cameraId;
    this.armPending = true;
    this.error = null;
    this.guarded(this.deviceControl.setCameraArmed(id, armed), id, {
      next: outcome => {
        this.armPending = false;
        if (outcome === 'unavailable') {
          this.error = UNREACHABLE;
        } else {
          this.changed.emit();
        }
      },
      error: () => {
        this.armPending = false;
        this.error = UNREACHABLE;
      }
    });
  }

  /** Fuehrt die Callbacks nur aus, solange das Feld noch dieselbe Kamera zeigt. */
  private guarded<T>(
    request: Observable<T>, id: string, handlers: { next: (value: T) => void; error: () => void }
  ): void {
    const sameCamera = () => this.control.cameraId === id;
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: value => {
        if (sameCamera()) {
          handlers.next(value);
          this.cdr.markForCheck();
        }
      },
      error: () => {
        if (sameCamera()) {
          handlers.error();
          this.cdr.markForCheck();
        }
      }
    });
  }
}
