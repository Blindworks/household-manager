import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminBlaulichtComponent, parseList, toForm, toSettings } from './admin-blaulicht.component';
import { BlaulichtService } from '../../services/blaulicht.service';
import { BlaulichtSettings } from '../../models/blaulicht.model';

const SETTINGS: BlaulichtSettings = {
  presseportalEnabled: true,
  titleFilter: 'WETTERAUKREIS',
  ninaEnabled: true,
  dashboardArs: '064400000000',
  localArs: '064400003003',
  excludedProviders: ['dwd'],
  mainLocality: 'Bad Vilbel',
  districts: ['Heilsberg', 'Gronau'],
  neighbors: ['Karben'],
  nightStart: '20:00',
  nightEnd: '08:00',
  addendumAt: '12:00',
  cardUntil: '20:00'
};

describe('AdminBlaulichtComponent', () => {
  let api: jasmine.SpyObj<BlaulichtService>;

  beforeEach(() => {
    api = jasmine.createSpyObj<BlaulichtService>('BlaulichtService', ['getSettings', 'saveSettings']);
    api.getSettings.and.returnValue(of(SETTINGS));
    TestBed.configureTestingModule({
      imports: [AdminBlaulichtComponent],
      providers: [{ provide: BlaulichtService, useValue: api }]
    });
  });

  it('zerlegt Listen zeilen- und kommaweise und verwirft Leeres', () => {
    expect(parseList(' Heilsberg \n\nGronau, Massenheim\n')).toEqual(['Heilsberg', 'Gronau', 'Massenheim']);
  });

  it('wandelt Listen hin und zurück ohne Verlust', () => {
    expect(toSettings(toForm(SETTINGS))).toEqual(SETTINGS);
  });

  it('lädt die Einstellungen und zeigt die Ortsteile je Zeile', async () => {
    const fixture = TestBed.createComponent(AdminBlaulichtComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const districts = fixture.nativeElement.querySelector('textarea[name="districts"]') as HTMLTextAreaElement;
    expect(districts.value).toBe('Heilsberg\nGronau');
  });

  it('speichert den ganzen Satz und zeigt die Fehlermeldung des Backends', () => {
    api.saveSettings.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { message: 'Der Nachtrag muss nach dem Morgen-Briefing kommen.' }
    })));
    const fixture = TestBed.createComponent(AdminBlaulichtComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.form!.addendumAt = '07:00';

    component.save();

    expect(api.saveSettings).toHaveBeenCalledWith({ ...SETTINGS, addendumAt: '07:00' });
    expect(component.message()).toBe('Der Nachtrag muss nach dem Morgen-Briefing kommen.');
    expect(component.messageType()).toBe('error');
  });

  it('zeigt bei Ladefehler den Neu-laden-Knopf', () => {
    api.getSettings.and.returnValue(throwError(() => new Error('weg')));
    const fixture = TestBed.createComponent(AdminBlaulichtComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('konnten nicht geladen werden');
  });
});
