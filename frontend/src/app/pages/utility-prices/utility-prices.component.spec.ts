import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { UtilityPricesComponent } from './utility-prices.component';
import { UtilityPriceService } from '../../services/utility-price.service';
import { AuthService } from '../../services/auth.service';
import { MeterType } from '../../models/meter-reading.model';

describe('UtilityPricesComponent', () => {
  let fixture: ComponentFixture<UtilityPricesComponent>;
  let component: UtilityPricesComponent;
  let serviceSpy: jasmine.SpyObj<UtilityPriceService>;

  function setup(isAdmin: boolean): void {
    serviceSpy = jasmine.createSpyObj('UtilityPriceService',
      ['getAllPrices', 'getSettings', 'updateSettings', 'deletePrice']);
    serviceSpy.getAllPrices.and.returnValue(of([]));
    serviceSpy.getSettings.and.returnValue(of({ gasKwhPerM3: 10.63 }));
    serviceSpy.updateSettings.and.returnValue(of({ gasKwhPerM3: 11 }));

    TestBed.configureTestingModule({
      imports: [UtilityPricesComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: UtilityPriceService, useValue: serviceSpy },
        { provide: AuthService, useValue: { isAdmin: signal(isAdmin) } }
      ]
    });
    fixture = TestBed.createComponent(UtilityPricesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('fuehrt Wasser als dritten Zaehlertyp', () => {
    setup(true);
    expect(component.meterTypes).toEqual([MeterType.ELECTRICITY, MeterType.GAS, MeterType.WATER]);
  });

  it('laedt den Gasfaktor und zeigt ihn dem Admin', () => {
    setup(true);
    expect(component.gasKwhPerM3).toBe(10.63);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.pricing-settings')).not.toBeNull();
  });

  it('versteckt den Gasfaktor vor Nicht-Admins und laedt ihn nicht', () => {
    setup(false);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.pricing-settings')).toBeNull();
    expect(serviceSpy.getSettings).not.toHaveBeenCalled();
  });

  it('speichert den Gasfaktor', () => {
    setup(true);
    component.gasKwhPerM3 = 11;
    component.saveGasFactor();
    expect(serviceSpy.updateSettings).toHaveBeenCalledWith({ gasKwhPerM3: 11 });
    expect(component.successMessage).toContain('Gasfaktor');
  });
});
