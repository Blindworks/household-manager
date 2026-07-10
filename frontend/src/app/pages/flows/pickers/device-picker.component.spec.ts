import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { DevicePickerComponent } from './device-picker.component';
import { SmartDeviceService } from '../../../services/smart-device.service';

describe('DevicePickerComponent', () => {
  let deviceService: jasmine.SpyObj<SmartDeviceService>;

  beforeEach(async () => {
    deviceService = jasmine.createSpyObj('SmartDeviceService', ['getAllDevices']);
    deviceService.getAllDevices.and.returnValue(of([
      { id: 1, deviceName: 'Lampe' },
      { id: 2, deviceName: 'Stecker' }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [DevicePickerComponent],
      providers: [{ provide: SmartDeviceService, useValue: deviceService }]
    }).compileComponents();
  });

  it('loads options on init', () => {
    const fixture = TestBed.createComponent(DevicePickerComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.options().length).toBe(2);
  });

  it('keeps an unknown selected value as fallback label', () => {
    const fixture = TestBed.createComponent(DevicePickerComponent);
    fixture.componentRef.setInput('value', 999);
    fixture.detectChanges();
    expect(fixture.componentInstance.displayLabel()).toContain('nicht gefunden');
    expect(fixture.componentInstance.displayLabel()).toContain('999');
  });

  it('emits valueChange (as a number) on select', () => {
    const fixture = TestBed.createComponent(DevicePickerComponent);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.valueChange.subscribe((v: number) => (emitted = v));
    fixture.componentInstance.select('2');
    expect(emitted).toBe(2);
  });

  it('a real change event on the native <select> emits the chosen id as a number', () => {
    const fixture = TestBed.createComponent(DevicePickerComponent);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.valueChange.subscribe((v: number) => (emitted = v));

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    select.value = '2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(emitted).toBe(2);
  });

  // Same regression as EntityPickerComponent: a plain [value] binding on <select> is not
  // re-applied by Angular once new <option>s appear after async load, if the bound primitive
  // itself did not change. [ngModel] fixes it because NgSelectOption re-syncs on registration.
  it('re-displays an already-saved device id once options finish loading asynchronously', fakeAsync(() => {
    const devices$ = new Subject<any[]>();
    deviceService.getAllDevices.and.returnValue(devices$.asObservable());

    const fixture = TestBed.createComponent(DevicePickerComponent);
    fixture.componentRef.setInput('value', 2);
    fixture.detectChanges();

    let select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(select.options.length).toBe(1);

    devices$.next([
      { id: 1, deviceName: 'Lampe' },
      { id: 2, deviceName: 'Stecker' }
    ]);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    select = fixture.nativeElement.querySelector('select');
    expect(select.value).toBe('2');
    expect(fixture.componentInstance.displayLabel()).toBe('Stecker');
  }));
});
