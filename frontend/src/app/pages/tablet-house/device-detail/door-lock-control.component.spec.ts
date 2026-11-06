import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { DoorLockControlComponent } from './door-lock-control.component';
import { ActionOutcome, HouseDeviceControlService } from '../house-device-control.service';

class FakeControl {
  calls: number[] = [];
  response: Observable<ActionOutcome> = of('done');
  lockDoor(id: number) {
    this.calls.push(id);
    return this.response;
  }
}

describe('DoorLockControlComponent', () => {
  let fixture: ComponentFixture<DoorLockControlComponent>;
  let control: FakeControl;
  let changed: number;

  function create(locked: boolean | null): HTMLElement {
    fixture = TestBed.createComponent(DoorLockControlComponent);
    fixture.componentRef.setInput('control', { type: 'lock', smartlockId: 18241688572, locked });
    fixture.componentInstance.changed.subscribe(() => changed++);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLButtonElement>('button'));

  beforeEach(() => {
    control = new FakeControl();
    changed = 0;
    TestBed.configureTestingModule({
      imports: [DoorLockControlComponent],
      providers: [{ provide: HouseDeviceControlService, useValue: control }]
    });
  });

  it('locked false: genau ein Knopf „Verriegeln“; Tipp verriegelt und meldet changed', () => {
    const el = create(false);
    expect(buttons(el).length).toBe(1);
    expect(buttons(el)[0].textContent!.trim()).toBe('Verriegeln');
    buttons(el)[0].click();
    expect(control.calls).toEqual([18241688572]);
    expect(changed).toBe(1);
  });

  it('locked true: kein Knopf', () => {
    expect(buttons(create(true)).length).toBe(0);
  });

  it('locked null: kein Knopf', () => {
    expect(buttons(create(null)).length).toBe(0);
  });

  it('already meldet changed', () => {
    control.response = of('already');
    const el = create(false);
    buttons(el)[0].click();
    expect(changed).toBe(1);
  });

  it('unavailable zeigt „Gerät nicht erreichbar“ ohne changed', () => {
    control.response = of('unavailable');
    const el = create(false);
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerät nicht erreichbar');
    expect(changed).toBe(0);
  });

  it('Fehler zeigt „Gerät nicht erreichbar“', () => {
    control.response = throwError(() => new Error('x'));
    const el = create(false);
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerät nicht erreichbar');
  });

  it('waehrend des Schreibens ist der Knopf gesperrt', () => {
    control.response = new Observable<ActionOutcome>(() => undefined);
    const el = create(false);
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(buttons(el)[0].disabled).toBeTrue();
    buttons(el)[0].click();
    expect(control.calls.length).toBe(1);
  });
});
