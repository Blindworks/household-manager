import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CommunicatorAnswer, CommunicatorStatus } from '../../models/communicator.model';
import { CommunicatorService } from '../../services/communicator.service';
import { CommunicatorComponent, greetingFor } from './communicator.component';

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

  it('spielt den Startton vor der Aufnahme und den Endton nach dem Stoppen', async () => {
    setup();
    const order: string[] = [];
    const parts = component as unknown as {
      recorder: { start: (ready: () => Promise<void>) => Promise<boolean>; stop: () => Promise<Blob | null>; cancel: () => void };
      tones: { unlock: () => void; start: () => Promise<void>; stop: () => Promise<void> };
    };
    spyOn(parts.tones, 'unlock');
    spyOn(parts.tones, 'start').and.callFake(async () => { order.push('startton'); });
    spyOn(parts.tones, 'stop').and.callFake(async () => { order.push('endton'); });
    spyOn(parts.recorder, 'start').and.callFake(async ready => { await ready(); order.push('aufnahme'); return true; });
    spyOn(parts.recorder, 'stop').and.callFake(async () => { order.push('stopp'); return new Blob(['x']); });
    service.askAudio.and.returnValue(of(climateAnswer));

    component.onMicTap();
    component.onMicTap();
    await fixture.whenStable();
    expect(component.phase()).toBe('recording');
    expect(parts.recorder.start).toHaveBeenCalledTimes(1);
    component.onMicTap();
    await fixture.whenStable();

    expect(order).toEqual(['startton', 'aufnahme', 'stopp', 'endton']);
    expect(service.askAudio).toHaveBeenCalledTimes(1);
  });

  it('gibt den Knopf frei, wenn das Mikrofon verweigert wird', async () => {
    setup();
    const recorder = (component as unknown as { recorder: { start: () => Promise<boolean> } }).recorder;
    spyOn(recorder, 'start').and.rejectWith(new Error('nein'));
    component.onMicTap();
    await fixture.whenStable();
    expect(component.phase()).toBe('idle');
    expect(component.error()).toContain('Mikrofon');
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

  it('zeigt im leeren Zustand Begrüßung und Beispielfragen, die wie eine getippte Frage gestellt werden', () => {
    setup();
    service.askText.and.returnValue(of(climateAnswer));

    expect(el().querySelector('.welcome__title')?.textContent?.trim()).toBe(component.greeting);
    const examples = el().querySelectorAll<HTMLButtonElement>('.welcome__example');
    expect(examples.length).toBeGreaterThan(0);

    examples[0].click();
    fixture.detectChanges();

    expect(service.askText).toHaveBeenCalledWith(examples[0].textContent!.trim());
    expect(el().querySelector('.welcome')).toBeNull();
  });

  it('wählt die Begrüßung nach Tageszeit', () => {
    expect(greetingFor(7)).toBe('Guten Morgen');
    expect(greetingFor(14)).toBe('Hallo');
    expect(greetingFor(20)).toBe('Guten Abend');
    expect(greetingFor(2)).toBe('Gute Nacht');
  });
});
