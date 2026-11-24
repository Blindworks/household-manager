import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CommunicatorAnswer, CommunicatorStatus } from '../../models/communicator.model';
import { CommunicatorService } from '../../services/communicator.service';
import { CommunicatorComponent } from './communicator.component';

describe('CommunicatorComponent', () => {
  let fixture: ComponentFixture<CommunicatorComponent>;
  let component: CommunicatorComponent;
  let service: jasmine.SpyObj<CommunicatorService>;

  const climateAnswer: CommunicatorAnswer = {
    transcript: 'Wie warm ist es im Wohnzimmer?',
    cards: [{ title: 'Wohnzimmer', icon: 'home', blocks: [{ type: 'value', value: '20,4', unit: '°C' }], actions: [], questions: [] }]
  };

  function setup(status: CommunicatorStatus = { enabled: true, aiConfigured: true, speechConfigured: true }): void {
    service = jasmine.createSpyObj<CommunicatorService>('CommunicatorService', ['getStatus', 'askAudio', 'askText']);
    service.getStatus.and.returnValue(of(status));
    TestBed.configureTestingModule({
      imports: [CommunicatorComponent],
      providers: [
        provideRouter([]),
        { provide: CommunicatorService, useValue: service }
      ]
    });
    fixture = TestBed.createComponent(CommunicatorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('zeigt nach einer Textfrage die Frage und die Karte', () => {
    setup();
    service.askText.and.returnValue(of(climateAnswer));

    component.text = '  Wie warm ist es im Wohnzimmer?  ';
    component.submitText();
    fixture.detectChanges();

    expect(service.askText).toHaveBeenCalledOnceWith('Wie warm ist es im Wohnzimmer?');
    expect(el().querySelector('.communicator__transcript')?.textContent).toContain('Wie warm ist es im Wohnzimmer?');
    expect(el().querySelectorAll('app-communicator-card').length).toBe(1);
    expect(el().querySelector('.value__main')?.textContent).toContain('20,4');
    expect(el().querySelector('.communicator')?.classList).toContain('communicator--answered');
  });

  it('sperrt eine zweite Frage, solange die erste laeuft', () => {
    setup();
    const pending = new Subject<CommunicatorAnswer>();
    service.askText.and.returnValue(pending);

    component.askQuestion('Erste Frage');
    component.askQuestion('Zweite Frage');
    component.onMicTap();

    expect(service.askText).toHaveBeenCalledTimes(1);
    expect(component.phase()).toBe('thinking');
  });

  it('meldet einen Fehler und laesst die letzte Antwort stehen', () => {
    setup();
    service.askText.and.returnValues(of(climateAnswer), throwError(() => new Error('weg')));

    component.askQuestion('Wie warm ist es im Wohnzimmer?');
    component.askQuestion('Und im Bad?');
    fixture.detectChanges();

    expect(el().querySelector('.communicator__error')?.textContent).toContain('Keine Antwort');
    expect(el().querySelectorAll('app-communicator-card').length).toBe(1);
    expect(component.phase()).toBe('idle');
  });

  it('ersetzt bei einer neuen Antwort die Karten-Komponenten', () => {
    setup();
    service.askText.and.returnValue(of(climateAnswer));

    component.askQuestion('Frage');
    fixture.detectChanges();
    const first = el().querySelector('app-communicator-card');
    component.askQuestion('Frage');
    fixture.detectChanges();

    expect(el().querySelector('app-communicator-card')).not.toBe(first);
  });

  it('ohne eingerichtete Spracherkennung gibt es kein Mikrofon, aber das Textfeld', () => {
    setup({ enabled: true, aiConfigured: true, speechConfigured: false });

    expect(el().querySelector('.mic')).toBeNull();
    expect(el().querySelector('.communicator__input')).not.toBeNull();
  });

  it('stellt die Folgefrage eines Vorschlags', () => {
    setup();
    service.askText.and.returnValue(of({
      transcript: 'Hallo',
      cards: [{ title: 'Hinweis', icon: 'info', blocks: [{ type: 'text', text: 'Text' }], actions: [], questions: ['Wie wird das Wetter?'] }]
    }));
    component.askQuestion('Hallo');
    fixture.detectChanges();

    (el().querySelector('.suggestions__chip') as HTMLButtonElement).click();

    expect(service.askText.calls.mostRecent().args[0]).toBe('Wie wird das Wetter?');
  });
});
